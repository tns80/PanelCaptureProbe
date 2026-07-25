package org.boluo.panelprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.boluo.panelprobe.capture.CaptureProbeService
import org.boluo.panelprobe.capture.DiagnosticExporter
import org.boluo.panelprobe.shizuku.ShizukuBridge

class MainActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var testStatus: TextView
    private lateinit var latestResult: TextView
    private lateinit var authorizeButton: Button
    private lateinit var probeButton: Button
    private lateinit var forceOnButton: Button
    private lateinit var startButton: Button
    private lateinit var shareButton: Button
    private lateinit var acknowledgement: CheckBox

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        ShizukuBridge.onStatusChanged = {
            runOnUiThread { refreshStatus() }
        }
        ShizukuBridge.initialize(this)
        requestNotificationPermissionIfNeeded()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.initialize(this)
        refreshStatus()
    }

    override fun onDestroy() {
        if (ShizukuBridge.onStatusChanged != null) {
            ShizukuBridge.onStatusChanged = null
        }
        super.onDestroy()
    }

    @Deprecated("Uses the framework callback to avoid adding an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            toast("你取消了录屏授权，未开始测试")
            return
        }
        if (!ShizukuBridge.isReady()) {
            toast("Shizuku 控制服务已断开，请重新连接")
            return
        }
        try {
            CaptureProbeService.start(this, resultCode, Intent(data))
            testStatus.text =
                "诊断已启动：将返回游戏，倒计时 10 秒后关闭物理面板 20 秒。"
            mainHandler.postDelayed({ launchGame() }, GAME_LAUNCH_DELAY_MILLIS)
            refreshStatus()
        } catch (error: Throwable) {
            toast("无法启动诊断服务：${error.javaClass.simpleName}")
        }
    }

    private fun buildInterface() {
        window.statusBarColor = getColor(R.color.page_background)
        window.navigationBarColor = getColor(R.color.page_background)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(22))
            setBackgroundColor(getColor(R.color.page_background))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        content.addView(textView("物理面板 × 录屏捕获诊断", 26f, Color.WHITE, true))
        content.addView(
            textView(
                "独立 v${BuildConfig.VERSION_NAME} · 不识别商品 · 不点击游戏 · 不联网",
                15f,
                getColor(R.color.text_secondary),
            ).withBottomMargin(dp(12)),
        )

        content.addView(
            panel {
                addView(
                    textView(
                        "安全说明",
                        18f,
                        getColor(R.color.warning),
                        true,
                    ),
                )
                addView(
                    textView(
                        "测试期间不要开启红魔熄屏挂机，也不要按锁屏。授权录屏后，应用会返回游戏，" +
                            "倒计时 10 秒，再尝试关闭物理 OLED 20 秒。Shell 进程和本应用各有一次" +
                            "自动亮屏恢复；任何异常都可以短按物理电源键，若仍黑再按一次。",
                        15f,
                        getColor(R.color.text_primary),
                    ),
                )
                acknowledgement = CheckBox(this@MainActivity).apply {
                    text = "我已知道：若未亮屏，短按电源键；仍黑则再按一次"
                    setTextColor(getColor(R.color.warning))
                    textSize = 15f
                    setOnCheckedChangeListener { _, _ -> refreshButtons() }
                }
                addView(acknowledgement)
            }.withBottomMargin(dp(12)),
        )

        content.addView(
            panel {
                addView(textView("1. Shizuku", 18f, Color.WHITE, true))
                shizukuStatus = textView("", 15f, getColor(R.color.text_primary))
                addView(shizukuStatus)
                val actions = horizontalActions()
                authorizeButton = actionButton("连接 / 授权 Shizuku") {
                    if (ShizukuBridge.isBinderAlive()) {
                        ShizukuBridge.requestPermission()
                    } else {
                        if (!launchPackage(SHIZUKU_PACKAGE)) {
                            toast("请先安装并启动 Shizuku")
                        }
                    }
                }
                probeButton = actionButton("只检测控制接口") {
                    runBackground("检测物理显示接口") {
                        ShizukuBridge.probe() ?: "UserService 未连接"
                    }
                }
                forceOnButton = actionButton("强制亮屏") {
                    runBackground("亮屏恢复结果") {
                        ShizukuBridge.forcePanelOn("manual-button")
                    }
                }
                actions.addView(authorizeButton, weightedButtonParams())
                actions.addView(probeButton, weightedButtonParams())
                actions.addView(forceOnButton, weightedButtonParams())
                addView(actions)
            }.withBottomMargin(dp(12)),
        )

        content.addView(
            panel {
                addView(textView("2. 限时诊断", 18f, Color.WHITE, true))
                testStatus = textView("", 15f, getColor(R.color.text_primary))
                addView(testStatus)
                startButton = actionButton("授权录屏并开始") {
                    requestMediaProjection()
                }.apply {
                    setBackgroundResource(R.drawable.button_accent)
                    setTextColor(Color.rgb(5, 35, 32))
                }
                addView(
                    startButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) },
                )
            }.withBottomMargin(dp(12)),
        )

        content.addView(
            panel {
                addView(textView("3. 最近结果", 18f, Color.WHITE, true))
                latestResult = textView("", 15f, getColor(R.color.text_primary))
                addView(latestResult)
                shareButton = actionButton("分享最近诊断 ZIP") { shareLatestDiagnostic() }
                addView(
                    shareButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) },
                )
            },
        )

        setContentView(scroll)
    }

    private fun requestMediaProjection() {
        if (CaptureProbeService.isRunning.get()) {
            toast("已有诊断正在运行")
            return
        }
        if (!ShizukuBridge.isReady()) {
            toast("请先让 Shizuku 显示“已授权、已连接”")
            return
        }
        if (!acknowledgement.isChecked) {
            toast("请先勾选安全确认")
            return
        }
        if (packageManager.getLaunchIntentForPackage(BuildConfig.GAME_PACKAGE) == null) {
            toast("没有找到国际服游戏：${BuildConfig.GAME_PACKAGE}")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val consentIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(consentIntent, REQUEST_MEDIA_PROJECTION)
    }

    private fun launchGame() {
        if (!launchPackage(BuildConfig.GAME_PACKAGE)) {
            toast("无法打开游戏，正在停止并恢复面板")
            startService(
                Intent(this, CaptureProbeService::class.java)
                    .setAction(CaptureProbeService.ACTION_ABORT),
            )
        }
    }

    private fun launchPackage(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun refreshStatus() {
        shizukuStatus.text = ShizukuBridge.statusText()
        val running = CaptureProbeService.isRunning.get()
        testStatus.text = if (running) {
            "诊断运行中。请查看通知；通知中的“立即恢复并停止”可随时结束。"
        } else {
            "待机。开始前请让游戏停在你希望观察的页面；授权后应用会自动返回国际服。"
        }

        val preferences = getSharedPreferences(
            DiagnosticExporter.PREFERENCES_NAME,
            MODE_PRIVATE,
        )
        val latestName = preferences.getString(DiagnosticExporter.KEY_LATEST_NAME, null)
        val latestVerdict = preferences.getString(
            DiagnosticExporter.KEY_LATEST_VERDICT,
            null,
        )
        latestResult.text = if (latestName == null) {
            "还没有诊断结果。完成后 ZIP 会保存到 Download/PanelCaptureProbe。"
        } else {
            "结论：${latestVerdict ?: "尚未读取"}\n文件：$latestName"
        }
        refreshButtons()
    }

    private fun refreshButtons() {
        val ready = ShizukuBridge.isReady()
        val running = CaptureProbeService.isRunning.get()
        authorizeButton.isEnabled = !running
        probeButton.isEnabled = ready && !running
        forceOnButton.isEnabled = ready
        startButton.isEnabled = ready && acknowledgement.isChecked && !running
        val preferences = getSharedPreferences(
            DiagnosticExporter.PREFERENCES_NAME,
            MODE_PRIVATE,
        )
        shareButton.isEnabled =
            preferences.getString(DiagnosticExporter.KEY_LATEST_URI, null) != null
    }

    private fun shareLatestDiagnostic() {
        val preferences = getSharedPreferences(
            DiagnosticExporter.PREFERENCES_NAME,
            MODE_PRIVATE,
        )
        val uriText = preferences.getString(DiagnosticExporter.KEY_LATEST_URI, null)
        if (uriText == null) {
            toast("还没有可分享的诊断 ZIP")
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriText))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "分享诊断 ZIP"))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        }
    }

    private fun runBackground(title: String, block: () -> String) {
        Thread({
            val result = runCatching(block).getOrElse {
                "${it.javaClass.simpleName}: ${it.message ?: "no message"}"
            }
            runOnUiThread {
                testStatus.text = "$title：\n$result"
                refreshButtons()
            }
        }, "panel-probe-ui-action").start()
    }

    private fun panel(block: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_background)
            block()
        }

    private fun horizontalActions(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun actionButton(label: String, click: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setOnClickListener { click() }
        }

    private fun weightedButtonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
            topMargin = dp(8)
        }

    private fun textView(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun View.withBottomMargin(margin: Int): View = apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = margin }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_MEDIA_PROJECTION = 4108
        private const val REQUEST_NOTIFICATIONS = 4109
        private const val GAME_LAUNCH_DELAY_MILLIS = 600L
    }
}
