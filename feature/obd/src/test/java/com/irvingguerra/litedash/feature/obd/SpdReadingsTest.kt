package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SpdReadingsTest {
    @Test fun consumoDelCarroConservaLitrosPorCienKm() {
        assertEquals(6.5f, spdConsumption(6.5f)!!, 0.001f)
        for (value in listOf(null, 0f, -1f, 51f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(spdConsumption(value))
    }

    @Test fun velocidadCeroEsValidaYUnaLecturaViejaCaduca() {
        assertEquals(0f, spdSpeed(0f, 1_000, 4_000))
        assertNull(spdSpeed(80f, 1_000, 4_001))
        assertNull(spdSpeed(80f, 0, 1_000))
        assertNull(spdSpeed(80f, 1_001, 1_000))
        for (value in listOf(null, -1f, 401f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(spdSpeed(value, 1_000, 1_000))
    }

    @Test fun alPerderSPDLaVelocidadVuelveAlGPSYDesconectarLimpiaElCAN() {
        val bus = VehicleBus()
        bus.publish(Signal.SPEED, 75f, Source.GPS)
        bus.publish(Signal.SPEED, spdSpeed(80f, 1_000, 1_100), Source.CAN)
        assertEquals(80f, bus.value(Signal.SPEED))
        assertEquals(Source.CAN, bus.source(Signal.SPEED))
        bus.publish(Signal.SPEED, spdSpeed(80f, 1_000, 4_001), Source.CAN)
        assertEquals(75f, bus.value(Signal.SPEED))
        assertEquals(Source.GPS, bus.source(Signal.SPEED))
        bus.publish(Signal.VOLTAGE, 12.3f, Source.CAN)
        bus.clear(Source.CAN)
        assertNull(bus.value(Signal.VOLTAGE))
        assertEquals(75f, bus.value(Signal.SPEED))
    }

    @Test fun presionesDeSPDSeConviertenDeBarAKpaSinInventarSensores() {
        assertEquals(240f, spdTyreKpa(2.4f)!!, 0.01f)
        assertEquals(0f, spdTyreKpa(0f)!!, 0.01f)
        for (value in listOf(null, -0.1f, 6.1f, Float.NaN)) assertNull(spdTyreKpa(value))
    }

    @Test fun litrosYRpmSinValidarNoSePresentanComoPorcentajeORegimenConfirmado() {
        assertFalse(Signal.FUEL in SPD_SIGNALS)
        assertFalse(Signal.RPM in SPD_SIGNALS)
        assertFalse(Signal.COOLANT in SPD_SIGNALS)
    }
}
