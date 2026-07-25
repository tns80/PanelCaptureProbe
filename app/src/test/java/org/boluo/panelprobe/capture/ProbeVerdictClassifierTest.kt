package org.boluo.panelprobe.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeVerdictClassifierTest {
    @Test
    fun `control rejection is reported before frame analysis`() {
        assertEquals(
            ProbeVerdict.PANEL_CONTROL_FAILED,
            classify(panelControlAccepted = false),
        )
    }

    @Test
    fun `projection stop is distinct from no frames`() {
        assertEquals(
            ProbeVerdict.PROJECTION_STOPPED,
            classify(projectionStopped = true),
        )
    }

    @Test
    fun `short observation is not treated as continued capture`() {
        assertEquals(
            ProbeVerdict.NO_FRAMES,
            classify(offFrameCount = 100, offFrameSpanMillis = 4_999),
        )
    }

    @Test
    fun `mostly black frames are rejected`() {
        assertEquals(
            ProbeVerdict.BLACK_CAPTURE,
            classify(
                offFrameCount = 100,
                nonBlackOffFrameCount = 59,
                distinctOffHashes = 10,
            ),
        )
    }

    @Test
    fun `visible but unchanged frames remain inconclusive`() {
        assertEquals(
            ProbeVerdict.VISIBLE_BUT_STATIC,
            classify(
                offFrameCount = 100,
                nonBlackOffFrameCount = 100,
                distinctOffHashes = 2,
            ),
        )
    }

    @Test
    fun `visible changing frames report continued capture`() {
        assertEquals(
            ProbeVerdict.CAPTURE_CONTINUES,
            classify(
                offFrameCount = 100,
                nonBlackOffFrameCount = 95,
                distinctOffHashes = 3,
            ),
        )
    }

    @Test
    fun `cancelled run never reports compatibility`() {
        assertEquals(
            ProbeVerdict.CANCELLED,
            classify(
                cancelled = true,
                offFrameCount = 100,
                nonBlackOffFrameCount = 100,
                distinctOffHashes = 100,
            ),
        )
    }

    private fun classify(
        panelControlAccepted: Boolean = true,
        projectionStopped: Boolean = false,
        offFrameCount: Int = 100,
        nonBlackOffFrameCount: Int = 100,
        distinctOffHashes: Int = 10,
        offFrameSpanMillis: Long = 18_000,
        cancelled: Boolean = false,
        internalError: Boolean = false,
    ): ProbeVerdict = ProbeVerdictClassifier.classify(
        VerdictInput(
            panelControlAccepted = panelControlAccepted,
            projectionStopped = projectionStopped,
            offFrameCount = offFrameCount,
            nonBlackOffFrameCount = nonBlackOffFrameCount,
            distinctOffHashes = distinctOffHashes,
            offFrameSpanMillis = offFrameSpanMillis,
            cancelled = cancelled,
            internalError = internalError,
        ),
    )
}
