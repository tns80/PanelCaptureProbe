import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
}

fun prop(name: String): String = providers.gradleProperty(name).get()
fun intProp(name: String): Int = prop(name).toInt()
fun String.capitalized(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

android {
    namespace = "org.boluo.panelprobe"
    compileSdk = intProp("app.compileSdk")

    defaultConfig {
        applicationId = prop("app.applicationId")
        minSdk = intProp("app.minSdk")
        targetSdk = intProp("app.targetSdk")
        versionCode = intProp("app.versionCode")
        versionName = prop("app.versionName")

        buildConfigField("String", "GAME_PACKAGE", "\"com.stove.epic7.google\"")
        buildConfigField("long", "PANEL_OFF_DURATION_MILLIS", "20_000L")
        buildConfigField("long", "COUNTDOWN_MILLIS", "10_000L")
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit4)
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val outputName = "PanelCaptureProbe-v${prop("app.versionName")}-debug.apk"
        val copyTask = tasks.register<Copy>("copy${variant.name.capitalized()}ApkToDist") {
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { outputName }
            }
            into(rootProject.layout.projectDirectory.dir("dist"))
        }
        tasks.configureEach {
            if (name == "assemble${variant.name.capitalized()}") {
                finalizedBy(copyTask)
            }
        }
    }
}

tasks.register("verifySafetyPolicy") {
    group = "verification"
    description = "Checks that the diagnostic stays offline, click-free and recoverable."
    doLast {
        val sourceRoot = projectDir.resolve("src/main")
        val manifest = sourceRoot.resolve("AndroidManifest.xml").readText()
        val activeFiles = fileTree(sourceRoot) {
            include("**/*.kt", "**/*.java", "**/*.aidl", "**/*.xml")
        }.files
        val activeText = activeFiles.joinToString("\n") { it.readText() }
        val userService = sourceRoot
            .resolve("java/org/boluo/panelprobe/shizuku/PanelControlUserService.kt")
            .readText()
        val captureService = sourceRoot
            .resolve("java/org/boluo/panelprobe/capture/CaptureProbeService.kt")
            .readText()

        listOf(
            "android.permission.INTERNET",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.accessibilityservice.AccessibilityService",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
        ).forEach { forbidden ->
            check(forbidden !in manifest) { "Forbidden capability in manifest: $forbidden" }
        }
        check("MAX_PANEL_OFF_MILLIS = 30_000L" in userService) {
            "Shell-side maximum panel-off duration must remain 30 seconds"
        }
        check("PANEL_OFF_DURATION_MILLIS\", \"20_000L\"" in
            projectDir.resolve("build.gradle.kts").readText()) {
            "The diagnostic panel-off interval must remain fixed at 20 seconds"
        }
        check(
            "finally" in userService &&
                "setPowerMode(PhysicalDisplayController.POWER_MODE_NORMAL)" in userService,
        ) {
            "The Shizuku user service must restore normal display power in finally"
        }
        check(
            "BACKUP_RESTORE_DELAY_MILLIS = 22_000L" in captureService &&
                "forcePanelOn(\"main-process-backup\")" in captureService,
        ) {
            "The foreground service must retain its independent 22-second restore"
        }
        listOf(
            "KEYCODE_POWER",
            "input keyevent",
            "goToSleep(",
            "lockNow(",
            "performGlobalAction(",
            "dispatchGesture(",
        ).forEach { forbidden ->
            check(forbidden !in activeText) { "Forbidden lock/click primitive: $forbidden" }
        }
    }
}
