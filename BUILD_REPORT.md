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

## GitHub 预期产物

成功工作流应生成 artifact：

```text
PanelCaptureProbe-v0.1.0-apk
├── PanelCaptureProbe-v0.1.0-debug.apk
└── SHA256SUMS.txt
```
