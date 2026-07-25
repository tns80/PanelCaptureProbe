# PanelCaptureProbe v0.1.0 检查报告

## 范围

本项目只用于验证物理显示面板关闭后 MediaProjection 的帧可用性。它与现有
E7BookmarkNative v0.2.4 分离，未修改稳定版源码。

## 静态安全边界

- Manifest 不申请网络、悬浮窗、写系统设置或无障碍权限。
- 源码没有游戏点击、滑动、锁屏、电源键模拟或全局无障碍动作。
- 面板关闭测试固定 20 秒。
- Shizuku UserService 接受的关闭时长上限固定为 30 秒。
- Shell 侧恢复位于 `finally`。
- 主进程第 22 秒执行独立恢复。
- 启动连接 UserService 时执行一次亮屏恢复。
- 通知提供“立即恢复并停止”。

## 尚需真机验证

- 红魔 11 Pro 当前系统能否让 shell UID 调用隐藏显示控制接口。
- OLED 是否肉眼确认关闭，而非显示全黑画面。
- 面板关闭期间 MediaProjection 是否继续产生非黑且变化的帧。
- 20 秒 shell 恢复、22 秒应用恢复和物理电源键恢复是否都有效。

## 本轮实际验证

| 检查 | 结果 |
|---|---|
| Manifest 权限白名单 | 通过 |
| 禁止的锁屏/电源键/点击原语扫描 | 通过 |
| 20 秒测试值、30 秒硬上限和双重恢复锚点 | 通过 |
| XML 语法解析 | 通过 |
| GitHub Actions YAML 解析 | 通过 |
| UTF-8、LF、尾随空格和冲突标记 | 通过 |
| Gradle Wrapper 完整性 | 通过 |
| Gradle 配置、Kotlin 编译、单元测试、Lint、APK | 当前工作区未执行 |

最后一项不是源码失败：当前工作区没有 Android SDK 或 Gradle 9.5.0 本体，且网络策略阻止
访问 `services.gradle.org`。实际尝试在下载 Gradle 发行包时以
`java.net.SocketException: Network is unreachable` 停止，尚未进入项目配置或编译阶段。

仓库内 GitHub Actions 会依次执行：

```text
verifySafetyPolicy
testDebugUnitTest
lintDebug
assembleDebug
```

因此第一次 GitHub Actions 绿色成功是安装 APK 前的构建门槛；不能把本报告中的源码级检查
表述为“APK 已构建通过”。
