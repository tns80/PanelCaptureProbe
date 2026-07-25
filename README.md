# PanelCaptureProbe（物理面板 × 录屏捕获诊断）

这是一个与 E7 书签助手完全分开的非 Root Android 实验项目。它只回答一个问题：

> 红魔 11 Pro 上，通过 Shizuku 的 shell 权限关闭物理 OLED 面板时，Android
> `MediaProjection` 是否还能持续取得可见且变化的屏幕帧？

它不会识别或购买商品，不会点击/滑动游戏，不使用无障碍，不修改系统亮度，不联网，也不会读取游戏进程内部数据。

## 固定安全流程

1. 用户主动授权 Shizuku 和全屏录制。
2. 应用返回国际服 `com.stove.epic7.google`。
3. 屏幕保持正常显示并倒计时 10 秒。
4. Shizuku UserService 尝试把物理显示电源模式设为 `OFF`。
5. 独立的 shell 侧计时器在 20 秒后必定从 `finally` 请求 `NORMAL`。
6. 正常应用进程在第 22 秒再执行一次强制亮屏。
7. 保存少量截图、帧统计和控制结果到本机
   `Download/PanelCaptureProbe/PanelCaptureProbe-*.zip`。

如果应用进程意外退出，Shizuku 的 daemon UserService 仍持有 20 秒恢复计时器。物理电源键是最终人工恢复手段；若第一次按键只是让系统进入锁定状态而仍未亮屏，再短按一次唤醒。

## 重要限制

- 不要同时启用红魔“熄屏挂机”，也不要让手机进入锁屏。Android 可能在锁屏时终止 MediaProjection。
- 应用无法通过公开 API 证明 OLED 物理上确实断电，必须由测试者肉眼确认。
- “可见帧持续变化”仅说明屏幕捕获路径可能可用，不等于读取了游戏内部数据，也不证明长期挂机稳定。
- 隐藏显示 API 和厂商 SurfaceFlinger 策略可能拒绝 shell UID；失败会记录，不会绕过系统限制。
- 本项目固定最多关闭 20 秒，UserService 的硬上限为 30 秒。

## 手机测试步骤

1. 安装并启动 Shizuku。非 Root 模式需要无线调试；手机重启后要重新启动 Shizuku。
2. 安装 GitHub Actions 生成的 `PanelCaptureProbe-v0.1.0-debug.apk`。
3. 打开国际服游戏，停在神秘商店或其他带动画、数字变化的页面。
4. 回到“面板捕获诊断”，点“连接 / 授权 Shizuku”。
5. 先点“只检测控制接口”。这一步不会关闭屏幕；应看到 shell UID 和物理显示 token 信息。
6. 勾选安全确认，点“授权录屏并开始”，在系统窗口中授权录制整个屏幕。
7. 应用返回游戏。10 秒后观察 OLED 是否真的变黑；不要锁屏。
8. 等待约 22 秒，应自动恢复显示。若未恢复，短按物理电源键；仍黑则再短按一次。
9. 返回诊断应用查看结论，并分享最近的诊断 ZIP。

请同时记录两个肉眼结果：

- 面板是否确实全黑；
- 恢复后游戏是否仍在原页面并继续动画。

## 用 GitHub Actions 构建

把本项目全部文件放在新仓库根目录，确认根目录能直接看到
`settings.gradle.kts`、`gradlew`、`app/` 和 `.github/`。

进入仓库：

1. 点 `Actions`；
2. 选择 `Android CI`；
3. 点 `Run workflow`；
4. 等绿色成功标记；
5. 打开这次运行，在 `Artifacts` 下载
   `PanelCaptureProbe-v0.1.0-apk`；
6. 解压后安装 `PanelCaptureProbe-v0.1.0-debug.apk`。

## 本地构建

要求 JDK 17 和 Android SDK 36：

```bash
./gradlew verifySafetyPolicy testDebugUnitTest lintDebug assembleDebug
```

APK 会复制到：

```text
dist/PanelCaptureProbe-v0.1.0-debug.apk
```

## 诊断结论含义

| 结论 | 含义 |
|---|---|
| `CAPTURE_CONTINUES` | 面板关闭期间持续收到非黑且变化的帧，值得继续做下一阶段验证 |
| `VISIBLE_BUT_STATIC` | 有非黑帧但内容几乎不变，不能确认游戏画面仍更新 |
| `BLACK_CAPTURE` | 帧主要为黑色，不能用于原有像素识别 |
| `NO_FRAMES` | 面板关闭期间没有足够时长的帧 |
| `PROJECTION_STOPPED` | 系统终止了 MediaProjection |
| `PANEL_CONTROL_FAILED` | 当前 ROM 拒绝或缺少可用的显示电源控制接口 |

## 技术基线

- `applicationId`: `org.boluo.panelprobe`
- `versionName`: `0.1.0`
- `minSdk`: 29
- `targetSdk` / `compileSdk`: 36
- Gradle 9.5.0 / Android Gradle Plugin 9.3.0 / JDK 17
- Shizuku API / Provider 13.1.5
- 无签名 release 配置；GitHub 构建产物为可直接安装的 debug APK

第三方来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
