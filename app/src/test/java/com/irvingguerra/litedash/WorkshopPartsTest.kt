package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.obd.SpdState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopPartsTest {
    // ---- El valor bajo el dedo en las gráficas en vivo (lo más nuevo a la derecha) ----

    @Test
    fun rightEdgeIsTheNewest() = assertEquals(3f, pointAt(listOf(1f, 2f, 3f), 1f, capacity = 5))

    @Test
    fun leftEdgeOfAFullChartIsTheOldest() = assertEquals(1f, pointAt(listOf(1f, 2f, 3f, 4f, 5f), 0f, capacity = 5))

    @Test
    fun whereThereIsNoDataYetIsNull() = assertNull(pointAt(listOf(1f, 2f), 0f, capacity = 5))

    @Test
    fun middlePicksTheRoundedPoint() = assertEquals(4f, pointAt(listOf(1f, 2f, 3f, 4f, 5f), 0.7f, capacity = 5))

    // ---- Estados de la conexión integrada SPD ----

    @Test
    fun everySpdStateHasItsOwnText() {
        val texts = SpdState.entries.map { spdConnectionText(true, it) }
        assertEquals(SpdState.entries.size, texts.toSet().size)
    }

    @Test
    fun missingServiceAlwaysExplainsThatSPDIsUnavailable() {
        for (state in SpdState.entries) assertEquals(R.string.spd_unavailable, spdConnectionText(false, state))
    }
}
