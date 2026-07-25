package org.boluo.panelprobe.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import org.boluo.panelprobe.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuBridge {
    private const val PERMISSION_REQUEST_CODE = 4107

    private val initialized = AtomicBoolean(false)
    private val binding = AtomicBoolean(false)
    private val connectionLock = Object()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var panelControl: IPanelControl? = null

    @Volatile
    var onStatusChanged: (() -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        notifyChanged()
        ensureBound()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        panelControl = null
        binding.set(false)
        notifyChanged()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE &&
                grantResult == PackageManager.PERMISSION_GRANTED
            ) {
                ensureBound()
            }
            notifyChanged()
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            panelControl = IPanelControl.Stub.asInterface(service)
            binding.set(false)
            synchronized(connectionLock) {
                connectionLock.notifyAll()
            }
            Thread({
                runCatching { panelControl?.forcePanelOn() }
                notifyChanged()
            }, "startup-panel-recovery").start()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            panelControl = null
            binding.set(false)
            notifyChanged()
        }
    }

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
        ensureBound()
    }

    fun requestPermission() {
        if (!isBinderAlive()) {
            notifyChanged()
            return
        }
        if (hasPermission()) {
            ensureBound()
        } else {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            notifyChanged()
        }
    }

    fun ensureBound() {
        val context = applicationContext ?: return
        if (!isBinderAlive() || !hasPermission() || panelControl != null) return
        if (!binding.compareAndSet(false, true)) return
        try {
            Shizuku.bindUserService(userServiceArgs(context), serviceConnection)
        } catch (_: Throwable) {
            binding.set(false)
            notifyChanged()
        }
    }

    fun isReady(): Boolean = isBinderAlive() && hasPermission() && panelControl != null

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuUid(): Int? = runCatching { Shizuku.getUid() }.getOrNull()

    fun awaitControl(timeoutMillis: Long): IPanelControl? {
        panelControl?.let { return it }
        ensureBound()
        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(connectionLock) {
            while (panelControl == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                connectionLock.wait(remaining)
            }
        }
        return panelControl
    }

    fun probe(): String? = panelControl?.probe()

    fun forcePanelOn(reason: String): String {
        val service = awaitControl(2_000)
            ?: return """{"success":false,"reason":"$reason: Shizuku UserService unavailable"}"""
        return runCatching { service.forcePanelOn() }
            .getOrElse {
                """{"success":false,"reason":"$reason: ${it.javaClass.simpleName}"}"""
            }
    }

    fun statusText(): String {
        val binder = isBinderAlive()
        val permission = hasPermission()
        val service = panelControl != null
        return buildString {
            append("Shizuku：")
            append(if (binder) "运行中" else "未运行或未连接")
            append("\n授权：")
            append(if (permission) "已授权" else "未授权")
            append("\nShell UID：")
            append(shizukuUid()?.toString() ?: "尚未取得")
            append("\n面板控制服务：")
            append(if (service) "已连接" else if (binding.get()) "连接中" else "未连接")
        }
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PanelControlUserService::class.java.name),
        )
            .daemon(true)
            .tag("panel-capture-probe")
            .version(1)
            .debuggable(BuildConfig.DEBUG)
            .processNameSuffix("panel_control")

    private fun notifyChanged() {
        onStatusChanged?.invoke()
    }
}
