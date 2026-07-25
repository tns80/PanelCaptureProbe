package org.boluo.panelprobe.shizuku

import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Minimal physical-display power bridge adapted from scrcpy's display-control approach.
 *
 * It is intentionally kept inside the Shizuku UserService process. Normal app code must never
 * invoke these hidden APIs directly. See THIRD_PARTY_NOTICES.md for attribution.
 */
class PhysicalDisplayController {
    data class ControlReport(
        val success: Boolean,
        val requestedMode: Int,
        val strategy: String,
        val physicalDisplayIds: LongArray,
        val tokenCount: Int,
        val error: String? = null,
    ) {
        fun toJsonObject(): JSONObject = JSONObject()
            .put("success", success)
            .put("requestedMode", requestedMode)
            .put("strategy", strategy)
            .put("physicalDisplayIds", JSONArray(physicalDisplayIds.toList()))
            .put("tokenCount", tokenCount)
            .put("error", error ?: JSONObject.NULL)

        fun toJson(): String = toJsonObject().toString()
    }

    private data class DisplayAccess(
        val strategy: String,
        val physicalDisplayIds: LongArray,
        val tokens: List<IBinder>,
        val setPowerMode: Method,
    )

    @Volatile
    private var cachedAccess: DisplayAccess? = null

    fun probe(): String {
        return try {
            val access = access()
            JSONObject()
                .put("success", access.tokens.isNotEmpty())
                .put("sdk", Build.VERSION.SDK_INT)
                .put("strategy", access.strategy)
                .put("physicalDisplayIds", JSONArray(access.physicalDisplayIds.toList()))
                .put("tokenCount", access.tokens.size)
                .toString()
        } catch (error: Throwable) {
            failureJson("probe", error)
        }
    }

    fun setPowerMode(mode: Int): ControlReport {
        require(mode == POWER_MODE_OFF || mode == POWER_MODE_NORMAL) {
            "Unsupported display power mode: $mode"
        }
        return try {
            val access = access()
            check(access.tokens.isNotEmpty()) { "No physical display token was found" }
            access.tokens.forEach { token ->
                access.setPowerMode.invoke(null, token, mode)
            }
            ControlReport(
                success = true,
                requestedMode = mode,
                strategy = access.strategy,
                physicalDisplayIds = access.physicalDisplayIds,
                tokenCount = access.tokens.size,
            )
        } catch (error: Throwable) {
            cachedAccess = null
            ControlReport(
                success = false,
                requestedMode = mode,
                strategy = "unavailable",
                physicalDisplayIds = longArrayOf(),
                tokenCount = 0,
                error = compactError(error),
            )
        }
    }

    private fun access(): DisplayAccess {
        cachedAccess?.let { return it }
        return synchronized(this) {
            cachedAccess ?: buildAccess().also { cachedAccess = it }
        }
    }

    private fun buildAccess(): DisplayAccess {
        val failures = mutableListOf<String>()
        val surfaceControl = Class.forName("android.view.SurfaceControl")

        runCatching {
            createAccess(
                tokenOwner = surfaceControl,
                powerOwner = surfaceControl,
                strategy = "SurfaceControl.physicalDisplays",
            )
        }.onSuccess { return it }
            .onFailure { failures += "SurfaceControl physical IDs: ${compactError(it)}" }

        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                val displayControl = loadDisplayControlClass()
                createAccess(
                    tokenOwner = displayControl,
                    powerOwner = surfaceControl,
                    strategy = "DisplayControl.tokens+SurfaceControl.power",
                )
            }.onSuccess { return it }
                .onFailure { failures += "DisplayControl tokens: ${compactError(it)}" }

            runCatching {
                val displayControl = loadDisplayControlClass()
                createAccess(
                    tokenOwner = displayControl,
                    powerOwner = displayControl,
                    strategy = "DisplayControl.physicalDisplays",
                )
            }.onSuccess { return it }
                .onFailure { failures += "DisplayControl full path: ${compactError(it)}" }
        }

        runCatching {
            createLegacyAccess(surfaceControl)
        }.onSuccess { return it }
            .onFailure { failures += "SurfaceControl internal display: ${compactError(it)}" }

        error(failures.joinToString(separator = " | "))
    }

    private fun createAccess(
        tokenOwner: Class<*>,
        powerOwner: Class<*>,
        strategy: String,
    ): DisplayAccess {
        val getIds = findStaticMethod(tokenOwner, "getPhysicalDisplayIds", 0)
        val getToken = findStaticMethod(tokenOwner, "getPhysicalDisplayToken", 1)
        val setMode = findStaticMethod(powerOwner, "setDisplayPowerMode", 2)
        val ids = getIds.invoke(null) as? LongArray
            ?: error("${tokenOwner.name}.getPhysicalDisplayIds returned no long[]")
        val tokens = mutableListOf<IBinder>()
        for (id in ids) {
            val token = getToken.invoke(null, id) as? IBinder
            if (token != null) tokens += token
        }
        check(tokens.isNotEmpty()) { "No token returned for ${ids.size} physical display(s)" }
        return DisplayAccess(strategy, ids, tokens, setMode)
    }

    private fun createLegacyAccess(surfaceControl: Class<*>): DisplayAccess {
        val token = runCatching {
            findStaticMethod(surfaceControl, "getInternalDisplayToken", 0)
                .invoke(null) as? IBinder
        }.getOrNull() ?: run {
            findStaticMethod(surfaceControl, "getBuiltInDisplay", 1)
                .invoke(null, 0) as? IBinder
        } ?: error("No internal display token")
        return DisplayAccess(
            strategy = "SurfaceControl.internalDisplay",
            physicalDisplayIds = longArrayOf(),
            tokens = listOf(token),
            setPowerMode = findStaticMethod(surfaceControl, "setDisplayPowerMode", 2),
        )
    }

    private fun findStaticMethod(owner: Class<*>, name: String, parameterCount: Int): Method {
        val method = (owner.declaredMethods.asSequence() + owner.methods.asSequence())
            .firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == parameterCount &&
                    Modifier.isStatic(it.modifiers)
            }
            ?: error("${owner.name}.$name/$parameterCount not found")
        method.isAccessible = true
        return method
    }

    private fun loadDisplayControlClass(): Class<*> {
        val classPath = System.getenv("SYSTEMSERVERCLASSPATH")
            ?.takeIf { it.isNotBlank() }
            ?: error("SYSTEMSERVERCLASSPATH is empty")
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val methods = factory.declaredMethods
            .filter { it.name == "createClassLoader" && Modifier.isStatic(it.modifiers) }
            .sortedByDescending { it.parameterTypes.size }
        var lastError: Throwable? = null
        for (method in methods) {
            try {
                method.isAccessible = true
                var stringIndex = 0
                val args = method.parameterTypes.map { type ->
                    when {
                        type == String::class.java ->
                            if (stringIndex++ == 0) classPath else null
                        ClassLoader::class.java.isAssignableFrom(type) ->
                            ClassLoader.getSystemClassLoader()
                        type == Int::class.javaPrimitiveType -> 0
                        type == Boolean::class.javaPrimitiveType -> true
                        else -> null
                    }
                }.toTypedArray()
                val loader = method.invoke(null, *args) as? ClassLoader ?: continue
                val displayControl =
                    loader.loadClass("com.android.server.display.DisplayControl")
                loadAndroidServersLibrary(displayControl, loader)
                return displayControl
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("Cannot load DisplayControl", lastError)
    }

    private fun loadAndroidServersLibrary(
        displayControlClass: Class<*>,
        loader: ClassLoader,
    ) {
        val runtime = Runtime.getRuntime()
        val classOwnerPath = runCatching {
            Runtime::class.java.getDeclaredMethod(
                "loadLibrary0",
                Class::class.java,
                String::class.java,
            ).apply { isAccessible = true }
                .invoke(runtime, displayControlClass, "android_servers")
        }
        if (classOwnerPath.isSuccess) return

        Runtime::class.java.getDeclaredMethod(
            "loadLibrary0",
            ClassLoader::class.java,
            String::class.java,
        ).apply { isAccessible = true }
            .invoke(runtime, loader, "android_servers")
    }

    private fun failureJson(operation: String, error: Throwable): String = JSONObject()
        .put("success", false)
        .put("operation", operation)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("error", compactError(error))
        .toString()

    private fun compactError(error: Throwable): String {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return "${current.javaClass.simpleName}: ${current.message ?: "no message"}"
            .take(MAX_ERROR_LENGTH)
    }

    companion object {
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
        private const val MAX_ERROR_LENGTH = 800
    }
}
