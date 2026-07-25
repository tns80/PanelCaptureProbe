package org.boluo.panelprobe.capture

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

data class FrameRecord(
    val elapsedRealtimeMillis: Long,
    val wallTimeMillis: Long,
    val relativeToPanelOffMillis: Long?,
    val meanLuma: Double,
    val nearBlackRatio: Double,
    val sampledHash: Long,
)

data class FrameAnalysisSnapshot(
    val totalFrameCount: Int,
    val offFrameCount: Int,
    val nonBlackOffFrameCount: Int,
    val distinctOffHashes: Int,
    val offFrameSpanMillis: Long,
    val projectionStopped: Boolean,
    val firstFrameElapsedRealtimeMillis: Long?,
    val lastFrameElapsedRealtimeMillis: Long?,
    val savedImages: List<String>,
    val records: List<FrameRecord>,
)

class FrameAnalyzer(
    private val sessionDirectory: File,
) {
    private data class SnapshotRequest(
        val label: String,
        val completed: CountDownLatch,
    )

    private val frameCount = AtomicInteger(0)
    private val offFrameCount = AtomicInteger(0)
    private val nonBlackOffFrameCount = AtomicInteger(0)
    private val firstFrameElapsed = AtomicLong(0)
    private val lastFrameElapsed = AtomicLong(0)
    private val panelOffStartElapsed = AtomicLong(0)
    private val panelRestoreElapsed = AtomicLong(0)
    private val projectionStopped = AtomicBoolean(false)
    private val pendingSnapshot = AtomicReference<SnapshotRequest?>(null)
    private val savedCheckpointLabels = mutableSetOf<String>()
    private val savedImages = mutableListOf<String>()
    private val records = mutableListOf<FrameRecord>()
    private var lastRecordedElapsed = 0L

    fun onImage(image: Image) {
        val now = SystemClock.elapsedRealtime()
        frameCount.incrementAndGet()
        firstFrameElapsed.compareAndSet(0, now)
        lastFrameElapsed.set(now)

        val metrics = sample(image)
        val offStart = panelOffStartElapsed.get()
        val restore = panelRestoreElapsed.get()
        val relative = offStart.takeIf { it > 0 }?.let { now - it }
        val isDuringOff =
            offStart > 0 && now >= offStart && (restore == 0L || now <= restore)
        if (isDuringOff) {
            offFrameCount.incrementAndGet()
            if (metrics.meanLuma >= MIN_VISIBLE_MEAN_LUMA &&
                metrics.nearBlackRatio < MAX_NEAR_BLACK_RATIO
            ) {
                nonBlackOffFrameCount.incrementAndGet()
            }
        }

        synchronized(this) {
            if (now - lastRecordedElapsed >= RECORD_INTERVAL_MILLIS || records.isEmpty()) {
                records += FrameRecord(
                    elapsedRealtimeMillis = now,
                    wallTimeMillis = System.currentTimeMillis(),
                    relativeToPanelOffMillis = relative,
                    meanLuma = metrics.meanLuma,
                    nearBlackRatio = metrics.nearBlackRatio,
                    sampledHash = metrics.hash,
                )
                lastRecordedElapsed = now
            }
        }

        pendingSnapshot.getAndSet(null)?.let { request ->
            try {
                saveImage(image, request.label)
            } finally {
                request.completed.countDown()
            }
        }

        if (isDuringOff && relative != null &&
            relative in 0..(PANEL_OBSERVATION_MILLIS + CHECKPOINT_GRACE_MILLIS)
        ) {
            val due = CHECKPOINTS_MILLIS.entries.firstOrNull { (label, target) ->
                relative >= target && synchronized(this) { label !in savedCheckpointLabels }
            }
            if (due != null) {
                synchronized(this) { savedCheckpointLabels += due.key }
                saveImage(image, due.key)
            }
        }
    }

    fun requestSnapshotAndAwait(label: String, timeoutMillis: Long): Boolean {
        val request = SnapshotRequest(label, CountDownLatch(1))
        if (!pendingSnapshot.compareAndSet(null, request)) return false
        val completed = request.completed.await(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) pendingSnapshot.compareAndSet(request, null)
        return completed
    }

    fun markPanelOff(elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()) {
        panelOffStartElapsed.set(elapsedRealtimeMillis)
    }

    fun markPanelRestored(elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()) {
        panelRestoreElapsed.set(elapsedRealtimeMillis)
    }

    fun markProjectionStopped() {
        projectionStopped.set(true)
    }

    @Synchronized
    fun snapshot(): FrameAnalysisSnapshot {
        val recordsCopy = records.toList()
        val offRecords = recordsCopy.filter {
            val relative = it.relativeToPanelOffMillis
            relative != null && relative in 0..PANEL_OBSERVATION_MILLIS
        }
        val firstOff = offRecords.firstOrNull()?.elapsedRealtimeMillis
        val lastOff = offRecords.lastOrNull()?.elapsedRealtimeMillis
        return FrameAnalysisSnapshot(
            totalFrameCount = frameCount.get(),
            offFrameCount = offFrameCount.get(),
            nonBlackOffFrameCount = nonBlackOffFrameCount.get(),
            distinctOffHashes = offRecords.map { it.sampledHash }.distinct().size,
            offFrameSpanMillis =
                if (firstOff != null && lastOff != null) max(0, lastOff - firstOff) else 0,
            projectionStopped = projectionStopped.get(),
            firstFrameElapsedRealtimeMillis = firstFrameElapsed.get().takeIf { it > 0 },
            lastFrameElapsedRealtimeMillis = lastFrameElapsed.get().takeIf { it > 0 },
            savedImages = savedImages.toList(),
            records = recordsCopy,
        )
    }

    @Synchronized
    fun writeTimeline(file: File) {
        file.bufferedWriter().use { writer ->
            writer.appendLine(
                "wall_time_ms,elapsed_realtime_ms,relative_to_panel_off_ms," +
                    "mean_luma,near_black_ratio,sampled_hash",
            )
            records.forEach { record ->
                writer.append(record.wallTimeMillis.toString())
                writer.append(',')
                writer.append(record.elapsedRealtimeMillis.toString())
                writer.append(',')
                writer.append(record.relativeToPanelOffMillis?.toString().orEmpty())
                writer.append(',')
                writer.append(String.format(Locale.US, "%.3f", record.meanLuma))
                writer.append(',')
                writer.append(String.format(Locale.US, "%.5f", record.nearBlackRatio))
                writer.append(',')
                writer.append(record.sampledHash.toString())
                writer.appendLine()
            }
        }
    }

    private data class PixelMetrics(
        val meanLuma: Double,
        val nearBlackRatio: Double,
        val hash: Long,
    )

    private fun sample(image: Image): PixelMetrics {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val columns = min(SAMPLE_COLUMNS, width)
        val rows = min(SAMPLE_ROWS, height)
        var lumaSum = 0.0
        var nearBlack = 0
        var count = 0
        var hash = FNV_OFFSET_BASIS

        for (row in 0 until rows) {
            val y = if (rows == 1) 0 else row * (height - 1) / (rows - 1)
            for (column in 0 until columns) {
                val x = if (columns == 1) 0 else column * (width - 1) / (columns - 1)
                val offset = y * rowStride + x * pixelStride
                if (offset + 2 >= buffer.limit()) continue
                val red = buffer.get(offset).toInt() and 0xff
                val green = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                val luma = (red * 299 + green * 587 + blue * 114) / 1000
                lumaSum += luma
                if (luma <= NEAR_BLACK_LUMA) nearBlack++
                count++
                val quantized = luma / HASH_LUMA_BUCKET
                hash = (hash xor quantized.toLong()) * FNV_PRIME
            }
        }
        if (count == 0) return PixelMetrics(0.0, 1.0, 0)
        return PixelMetrics(
            meanLuma = lumaSum / count,
            nearBlackRatio = nearBlack.toDouble() / count,
            hash = hash,
        )
    }

    private fun saveImage(image: Image, label: String) {
        runCatching {
            val plane = image.planes.first()
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth == image.width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            }
            val output = if (cropped.width > MAX_SNAPSHOT_WIDTH) {
                val targetHeight =
                    max(1, cropped.height * MAX_SNAPSHOT_WIDTH / cropped.width)
                Bitmap.createScaledBitmap(cropped, MAX_SNAPSHOT_WIDTH, targetHeight, true)
            } else {
                cropped
            }
            val fileName = "${label.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.png"
            FileOutputStream(File(sessionDirectory, fileName)).use { stream ->
                check(output.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            synchronized(this) {
                if (fileName !in savedImages) savedImages += fileName
            }
            if (output !== cropped) output.recycle()
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
        }
    }

    companion object {
        private const val SAMPLE_COLUMNS = 48
        private const val SAMPLE_ROWS = 27
        private const val RECORD_INTERVAL_MILLIS = 250L
        private const val PANEL_OBSERVATION_MILLIS = 20_000L
        private const val CHECKPOINT_GRACE_MILLIS = 1_000L
        private const val MAX_SNAPSHOT_WIDTH = 1280
        private const val NEAR_BLACK_LUMA = 8
        private const val MIN_VISIBLE_MEAN_LUMA = 12.0
        private const val MAX_NEAR_BLACK_RATIO = 0.95
        private const val HASH_LUMA_BUCKET = 8
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
        private val CHECKPOINTS_MILLIS = linkedMapOf(
            "panel_off_01s" to 1_000L,
            "panel_off_05s" to 5_000L,
            "panel_off_10s" to 10_000L,
            "panel_off_18s" to 18_000L,
        )
    }
}
