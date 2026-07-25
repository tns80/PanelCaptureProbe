# PanelCaptureProbe v0.1.0 构建报告

## 预期基线

- JDK 17
- Gradle Wrapper 9.5.0
- Android Gradle Plugin 9.3.0
- Android SDK 36 / Build Tools 36.0.0
- Shizuku API / Provider 13.1.5

## 当前工作区结果

已实际调用：

```bash
./gradlew verifySafetyPolicy testDebugUnitTest lintDebug assembleDebug \
  --stacktrace --no-daemon
```

Wrapper 尝试下载 `gradle-9.5.0-bin.zip`，但当前隔离工作区不能连接
`services.gradle.org`，四次尝试均返回 `java.net.SocketException: Network is unreachable`。

因此：

- Gradle 尚未进入项目配置阶段；
- Kotlin/AIDL 尚未在本地编译；
- JVM 单元测试尚未实际执行；
- Android Lint 尚未实际执行；
- 本地未生成 APK。

这不是“构建失败的源码错误”，也不能据此宣称源码一定能够构建。首次真实构建由
`.github/workflows/android-ci.yml` 完成；只有工作流全部变绿后，才能下载并安装
`PanelCaptureProbe-v0.1.0-debug.apk`。

## GitHub 第一次真实构建

GitHub Actions 已成功完成 Gradle 下载、项目配置、资源处理、Manifest 合并和 AIDL
编译，并在 Kotlin 编译阶段发现：

```text
PhysicalDisplayController.kt:151 Unresolved reference 'mapNotNull'
```

原因是 `ids` 为原生 `LongArray`，当前内置 Kotlin 编译环境不提供该处使用的
`mapNotNull` 扩展。现已最小替换为显式 `for` 循环和 `MutableList<IBinder>`，不改变
显示 token 的筛选逻辑。修复后的第二次 GitHub Actions 结果仍需实际确认。

## GitHub 第二次真实构建

第二次 Actions 已实际通过：

```text
compileDebugKotlin
compileDebugJavaWithJavac
compileDebugUnitTestKotlin
testDebugUnitTest
packageDebug
assembleDebug
copyDebugApkToDist
```

随后 `lintDebug` 在 `Runtime.loadLibrary0` 的反射处报告唯一错误
`BlockedPrivateApi`。这是 Shizuku shell-UID UserService 为加载 Android 14+
`DisplayControl` 所需的受控隐藏 API 路径，不在普通应用进程执行。现已只在该私有方法上
添加 `@SuppressLint("BlockedPrivateApi", "PrivateApi")` 和范围说明；没有关闭全局
Lint，也没有创建 baseline。第三次 CI 仍需确认 Lint、校验和与 artifact 上传完整成功。

## GitHub 预期产物

成功工作流应生成 artifact：

```text
PanelCaptureProbe-v0.1.0-apk
├── PanelCaptureProbe-v0.1.0-debug.apk
└── SHA256SUMS.txt
```
