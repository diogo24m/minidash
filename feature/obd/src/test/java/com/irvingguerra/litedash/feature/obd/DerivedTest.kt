package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DerivedTest {
    @Test
    fun consumoConMafYVelocidad() {
        // 5 g/s a 100 km/h ≈ 1.66 L/h → 1.66 L/100 km (en carretera, motor chico).
        val litresPer100Km = consumption(5f, 100f)!!
        assertEquals(1.66f, litresPer100Km, 0.01f)
        // Más aire a la misma velocidad = más gasolina por cada 100 km.
        assert(consumption(15f, 100f)!! > litresPer100Km)
    }

    @Test
    fun consumoParadoNoSeCalcula() {
        assertNull(consumption(3f, 0f))
        assertNull(consumption(0f, 80f))
    }

    @Test
    fun consumoPrefiereElGastoDeGasolina() {
        val consumo = DerivedSignals.first { it.signal == Signal.CONSUMPTION }
        // 100 km/h gastando 5 L/h = 5 L/100 km, aunque el MAF diga otra cosa.
        assertEquals(5f, consumo.compute(mapOf(Signal.SPEED to 100f, Signal.FUEL_RATE to 5f, Signal.MAF to 5f))!!, 0.01f)
        // Sin gasto de gasolina, la cuenta con el MAF.
        assertEquals(1.66f, consumo.compute(mapOf(Signal.SPEED to 100f, Signal.MAF to 5f))!!, 0.01f)
        // Parado no hay L/100 km.
        assertNull(consumo.compute(mapOf(Signal.SPEED to 0f, Signal.FUEL_RATE to 1f)))
    }

    @Test
    fun consumoSinMafNiGastoNoSeCalcula() {
        val consumo = DerivedSignals.first { it.signal == Signal.CONSUMPTION }
        assertNull(consumo.compute(mapOf(Signal.SPEED to 100f)))
    }

    @Test
    fun turboUsaLaPresionDelAireSiLaHay() {
        val boost = DerivedSignals.first { it.signal == Signal.BOOST }
        assertEquals(60f, boost.compute(mapOf(Signal.MAP to 138f, Signal.BARO to 78f))!!, 0.01f)
        // Sin presión del aire se usa la del nivel del mar.
        assertEquals(36.7f, boost.compute(mapOf(Signal.MAP to 138f))!!, 0.01f)
        assertNull(boost.compute(mapOf(Signal.BARO to 78f)))
    }

    @Test
    fun alertas() {
        assertEquals(EngineAlert.HOT, engineAlert(112f, 14f, 800f))
        // Sin rpm solo cuenta el calor.
        assertNull(engineAlert(95f, 11f, null))
        assertEquals(EngineAlert.NOT_CHARGING, engineAlert(90f, 12.2f, 850f))
        assertEquals(EngineAlert.OVERCHARGING, engineAlert(90f, 15.8f, 2000f))
        assertEquals(EngineAlert.LOW_BATTERY, engineAlert(null, 11.5f, 0f))
        assertNull(engineAlert(90f, 14.1f, 800f))
        assertNull(engineAlert(null, 12.5f, 0f))
    }
}
