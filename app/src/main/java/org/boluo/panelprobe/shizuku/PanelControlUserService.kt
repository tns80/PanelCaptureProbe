package org.boluo.panelprobe.shizuku

import android.os.Build
import android.os.Process
import org.json.JSONObject

/**
 * Runs in Shizuku's shell-UID UserService process.
 *
 * The restoration timer lives here instead of in the normal app process. Once a panel-off cycle
 * has been accepted, this process always requests POWER_MODE_NORMAL from a finally block.
 */
class PanelControlUserService : IPanelControl.Stub() {
    private val displayController = PhysicalDisplayController()

    @Volatile
    private var cycleThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var panelOff = false

    @Volatile
    private var lastStatus = "UserService created"

    override fun probe(): String = JSONObject()
        .put("uid", Process.myUid())
        .put("pid", Process.myPid())
        .put("sdk", Build.VERSION.SDK_INT)
        .put("display", JSONObject(displayController.probe()))
        .toString()

    @Synchronized
    override fun startTimedPanelOff(durationMillis: Int): String {
        if (running) {
            return JSONObject()
                .put("accepted", false)
                .put("reason", "A timed panel-off cycle is already running")
                .put("status", JSONObject(getCycleStatus()))
                .toString()
        }
        if (durationMillis !in MIN_PANEL_OFF_MILLIS..MAX_PANEL_OFF_MILLIS.toInt()) {
            return JSONObject()
                .put("accepted", false)
                .put("reason", "Duration outside safe range")
                .put("minimumMillis", MIN_PANEL_OFF_MILLIS)
                .put("maximumMillis", MAX_PANEL_OFF_MILLIS)
                .toString()
        }

        val offReport = displayController.setPowerMode(PhysicalDisplayController.POWER_MODE_OFF)
        if (!offReport.success) {
            displayController.setPowerMode(PhysicalDisplayController.POWER_MODE_NORMAL)
            lastStatus = "Panel-off request failed"
            return JSONObject()
                .put("accepted", false)
                .put("reason", "Physical display power API failed")
                .put("off", offReport.toJsonObject())
                .toString()
        }

        running = true
        panelOff = true
        lastStatus = "Panel off; shell-side restore timer running"
        cycleThread = Thread({
            try {
                Thread.sleep(durationMillis.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                val restore = displayController
                    .setPowerMode(PhysicalDisplayController.POWER_MODE_NORMAL)
                panelOff = false
                running = false
                lastStatus = if (restore.success) {
                    "Panel restored by shell-side finally"
                } else {
                    "Shell-side restore reported failure: ${restore.error}"
                }
                cycleThread = null
            }
        }, "panel-restore-timer").apply {
            isDaemon = true
            start()
        }

        return JSONObject()
            .put("accepted", true)
            .put("durationMillis", durationMillis)
            .put("off", offReport.toJsonObject())
            .put("uid", Process.myUid())
            .put("pid", Process.myPid())
            .toString()
    }

    @Synchronized
    override fun forcePanelOn(): String {
        cycleThread?.interrupt()
        cycleThread = null
        val restore =
            displayController.setPowerMode(PhysicalDisplayController.POWER_MODE_NORMAL)
        panelOff = false
        running = false
        lastStatus = if (restore.success) {
            "Panel forced on"
        } else {
            "Force-on request failed: ${restore.error}"
        }
        return JSONObject()
            .put("success", restore.success)
            .put("restore", restore.toJsonObject())
            .put("uid", Process.myUid())
            .put("pid", Process.myPid())
            .toString()
    }

    override fun getCycleStatus(): String = JSONObject()
        .put("running", running)
        .put("panelOff", panelOff)
        .put("status", lastStatus)
        .put("uid", Process.myUid())
        .put("pid", Process.myPid())
        .toString()

    override fun destroy() {
        runCatching { forcePanelOn() }
        System.exit(0)
    }

    companion object {
        private const val MIN_PANEL_OFF_MILLIS = 1_000
        const val MAX_PANEL_OFF_MILLIS = 30_000L
    }
}
