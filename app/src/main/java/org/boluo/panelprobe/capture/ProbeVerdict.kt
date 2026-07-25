package org.boluo.panelprobe.capture

enum class ProbeVerdict(
    val title: String,
    val explanation: String,
) {
    CAPTURE_CONTINUES(
        "面板关闭期间仍有可见且变化的帧",
        "录屏帧持续到达、不是黑帧，并出现内容变化。说明这台设备值得继续研究物理面板关闭方案。",
    ),
    VISIBLE_BUT_STATIC(
        "面板关闭期间有可见帧，但未确认内容更新",
        "录屏帧持续到达且不是黑帧，但采样内容几乎不变。可能是商店画面本身静止，也可能是合成画面冻结。",
    ),
    BLACK_CAPTURE(
        "面板关闭期间主要得到黑帧",
        "物理面板关闭后录屏帧仍可能到达，但绝大多数采样接近全黑，不能用于现有像素识别。",
    ),
    NO_FRAMES(
        "面板关闭期间没有录屏帧",
        "关闭物理面板后没有收到有效帧。当前方案不能直接支持识别。",
    ),
    PROJECTION_STOPPED(
        "系统停止了录屏投影",
        "MediaProjection 在面板关闭测试期间被系统终止。",
    ),
    PANEL_CONTROL_FAILED(
        "无法切换物理面板电源",
        "Shizuku 已连接，但系统或红魔固件拒绝/缺少当前物理显示控制接口。",
    ),
    CANCELLED(
        "测试被用户取消",
        "已经请求恢复面板并结束录屏；这次结果不用于兼容性判断。",
    ),
    INTERNAL_ERROR(
        "诊断过程发生错误",
        "请导出诊断包；仅凭本次结果无法判断兼容性。",
    ),
}

data class VerdictInput(
    val panelControlAccepted: Boolean,
    val projectionStopped: Boolean,
    val offFrameCount: Int,
    val nonBlackOffFrameCount: Int,
    val distinctOffHashes: Int,
    val offFrameSpanMillis: Long,
    val cancelled: Boolean,
    val internalError: Boolean,
)

object ProbeVerdictClassifier {
    fun classify(input: VerdictInput): ProbeVerdict {
        if (input.cancelled) return ProbeVerdict.CANCELLED
        if (input.internalError) return ProbeVerdict.INTERNAL_ERROR
        if (!input.panelControlAccepted) return ProbeVerdict.PANEL_CONTROL_FAILED
        if (input.projectionStopped) return ProbeVerdict.PROJECTION_STOPPED
        if (input.offFrameCount == 0 || input.offFrameSpanMillis < MIN_OBSERVATION_SPAN_MILLIS) {
            return ProbeVerdict.NO_FRAMES
        }
        val visibleRatio = input.nonBlackOffFrameCount.toDouble() / input.offFrameCount
        if (visibleRatio < MIN_VISIBLE_FRAME_RATIO) return ProbeVerdict.BLACK_CAPTURE
        if (input.distinctOffHashes < MIN_DISTINCT_HASHES) {
            return ProbeVerdict.VISIBLE_BUT_STATIC
        }
        return ProbeVerdict.CAPTURE_CONTINUES
    }

    private const val MIN_OBSERVATION_SPAN_MILLIS = 5_000L
    private const val MIN_VISIBLE_FRAME_RATIO = 0.60
    private const val MIN_DISTINCT_HASHES = 3
}
