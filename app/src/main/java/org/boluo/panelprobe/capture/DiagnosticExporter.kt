package org.boluo.panelprobe.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.boluo.panelprobe.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ProbeRunMetadata(
    val startedWallTimeMillis: Long,
    val completedWallTimeMillis: Long,
    val panelCommandResponse: String?,
    val backupRestoreResponse: String?,
    val finalShellStatus: String?,
    val cancelled: Boolean,
    val error: String?,
)

data class ExportedDiagnostic(
    val uri: Uri,
    val displayName: String,
    val verdict: ProbeVerdict,
)

class DiagnosticExporter(
    private val context: Context,
) {
    fun export(
        sessionDirectory: File,
        analyzer: FrameAnalyzer,
        metadata: ProbeRunMetadata,
    ): ExportedDiagnostic {
        val analysis = analyzer.snapshot()
        val panelAccepted = metadata.panelCommandResponse?.let {
            runCatching { JSONObject(it).optBoolean("accepted", false) }.getOrDefault(false)
        } ?: false
        val input = VerdictInput(
            panelControlAccepted = panelAccepted,
            projectionStopped = analysis.projectionStopped,
            offFrameCount = analysis.offFrameCount,
            nonBlackOffFrameCount = analysis.nonBlackOffFrameCount,
            distinctOffHashes = analysis.distinctOffHashes,
            offFrameSpanMillis = analysis.offFrameSpanMillis,
            cancelled = metadata.cancelled,
            internalError = metadata.error != null,
        )
        val verdict = ProbeVerdictClassifier.classify(input)
        analyzer.writeTimeline(File(sessionDirectory, "timeline.csv"))
        writeSummary(sessionDirectory, metadata, analysis, verdict)
        writeReadme(sessionDirectory)

        val displayName = "PanelCaptureProbe-${fileTimestamp(metadata.completedWallTimeMillis)}.zip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/PanelCaptureProbe",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create Downloads entry")
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    sessionDirectory.listFiles()
                        .orEmpty()
                        .filter { it.isFile }
                        .sortedBy { it.name }
                        .forEach { file ->
                            zip.putNextEntry(ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }

        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LATEST_URI, uri.toString())
            .putString(KEY_LATEST_NAME, displayName)
            .putString(KEY_LATEST_VERDICT, verdict.title)
            .apply()
        return ExportedDiagnostic(uri, displayName, verdict)
    }

    private fun writeSummary(
        directory: File,
        metadata: ProbeRunMetadata,
        analysis: FrameAnalysisSnapshot,
        verdict: ProbeVerdict,
    ) {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("verdict", verdict.name)
            .put("verdictTitle", verdict.title)
            .put("verdictExplanation", verdict.explanation)
            .put("startedAtUtc", isoTimestamp(metadata.startedWallTimeMillis))
            .put("completedAtUtc", isoTimestamp(metadata.completedWallTimeMillis))
            .put("durationMillis", metadata.completedWallTimeMillis - metadata.startedWallTimeMillis)
            .put("panelOffRequestedMillis", BuildConfig.PANEL_OFF_DURATION_MILLIS)
            .put("cancelled", metadata.cancelled)
            .put("error", metadata.error ?: JSONObject.NULL)
            .put("panelCommand", parseOrString(metadata.panelCommandResponse))
            .put("backupRestore", parseOrString(metadata.backupRestoreResponse))
            .put("finalShellStatus", parseOrString(metadata.finalShellStatus))
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("device", Build.DEVICE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE)
                    .put("displayBuild", Build.DISPLAY),
            )
            .put(
                "capture",
                JSONObject()
                    .put("totalFrameCount", analysis.totalFrameCount)
                    .put("offFrameCount", analysis.offFrameCount)
                    .put("nonBlackOffFrameCount", analysis.nonBlackOffFrameCount)
                    .put("distinctOffHashes", analysis.distinctOffHashes)
                    .put("offFrameSpanMillis", analysis.offFrameSpanMillis)
                    .put("projectionStopped", analysis.projectionStopped)
                    .put(
                        "firstFrameElapsedRealtimeMillis",
                        analysis.firstFrameElapsedRealtimeMillis ?: JSONObject.NULL,
                    )
                    .put(
                        "lastFrameElapsedRealtimeMillis",
                        analysis.lastFrameElapsedRealtimeMillis ?: JSONObject.NULL,
                    )
                    .put("savedImages", JSONArray(analysis.savedImages)),
            )
        File(directory, "summary.json").writeText(json.toString(2))
    }

    private fun writeReadme(directory: File) {
        File(directory, "说明.txt").writeText(
            """
            |PanelCaptureProbe v${BuildConfig.VERSION_NAME}
            |
            |本诊断包用于回答一个问题：Shizuku 关闭物理显示面板时，MediaProjection 是否仍能取得可见且变化的屏幕帧。
            |
            |重要限制：
            |1. 应用无法从系统 API 客观证明 OLED 面板确实已经断电；需要测试者肉眼确认。
            |2. “帧持续变化”不等于已读取游戏内部数据，也不证明长期挂机一定稳定。
            |3. 诊断只录制少量缩小截图和像素统计，不包含触摸、无障碍操作、网络上传。
            |4. ZIP 只保存在设备 Download/PanelCaptureProbe，除非用户主动分享，否则不会离开设备。
            |
            |文件：
            |- summary.json：结论、设备和控制结果
            |- timeline.csv：按时间采样的亮度、黑帧比例和帧哈希
            |- before_panel_off.png：关闭面板前
            |- panel_off_*.png：面板关闭期间的检查点（若当时有帧）
            |- after_panel_on.png：恢复面板后（若当时有帧）
            |""".trimMargin(),
        )
    }

    private fun parseOrString(value: String?): Any {
        if (value == null) return JSONObject.NULL
        return runCatching { JSONObject(value) }.getOrElse { value }
    }

    private fun isoTimestamp(timeMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timeMillis))
    }

    private fun fileTimestamp(timeMillis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(timeMillis))

    companion object {
        const val PREFERENCES_NAME = "panel_capture_probe"
        const val KEY_LATEST_URI = "latest_diagnostic_uri"
        const val KEY_LATEST_NAME = "latest_diagnostic_name"
        const val KEY_LATEST_VERDICT = "latest_diagnostic_verdict"
    }
}
