package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.vehicle.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La caja CAN de una pantalla SPD: qué pantalla se acepta, qué se le llama y cómo se leen sus datos. */
class SpdCarTest {
    @Test
    fun onlyCallsSubscriptionsAndReads() {
        assertEquals(setOf(1, 2, 9, 10), SpdCar.CALLS)
        // enterSource, exitSource y los setters (3 a 8) cambian cosas de la pantalla o del auto: nunca.
        for (code in 3..8) assertFalse(code in SpdCar.CALLS)
    }

    @Test
    fun onlyAVerifiedSystemServiceAndProfile() {
        val mini = 135_725_059
        assertTrue(SpdCheck(system = true, version = 25_383L, profile = mini).ok)
        // Otra versión podría tener otros códigos: no se le llama nada.
        assertFalse(SpdCheck(system = true, version = 25_384L, profile = mini).ok)
        // Una app instalada con el mismo nombre de paquete no es la de la pantalla.
        assertFalse(SpdCheck(system = false, version = 25_383L, profile = mini).ok)
        // Otro perfil CAN trae otros campos y escalas.
        assertFalse(SpdCheck(system = true, version = 25_383L, profile = 135_725_060).ok)
        assertFalse(SpdCheck(system = true, version = 25_383L, profile = null).ok)
    }

    @Test
    fun reportSaysWhatIsNotVerified() {
        assertEquals("servicio 25383 comprobado · perfil 135725059 comprobado", SpdCheck(true, 25_383L, 135_725_059).describe())
        assertEquals("servicio 99 sin comprobar (no es del sistema) · perfil ? sin comprobar", SpdCheck(false, 99L, null).describe())
    }

    @Test
    fun speedKeepsZeroAndDropsNonsense() {
        assertEquals(0f, spdSpeed(0f))
        assertEquals(6.516f, spdSpeed(6.516f))
        assertNull(spdSpeed(-1f))
        assertNull(spdSpeed(401f))
        assertNull(spdSpeed(Float.NaN))
    }

    @Test
    fun zeroWhileGpsSaysMovingIsNotBelieved() {
        // Parado de verdad: el GPS también (o casi) está quieto, o no hay GPS.
        assertTrue(spdSpeedTrusted(0f, 0f))
        assertTrue(spdSpeedTrusted(0f, 8f))
        assertTrue(spdSpeedTrusted(0f, null))
        // Sin dato su servicio también responde 0: con el GPS a 60 km/h, gana el GPS.
        assertFalse(spdSpeedTrusted(0f, 60f))
        // Lo que no es 0 se cree aunque el GPS diga otra cosa.
        assertTrue(spdSpeedTrusted(48f, 60f))
    }

    @Test
    fun fuelIsPercentOfTheTank() {
        // 14 L de un tanque de 40 L (lo visto en el MINI).
        assertEquals(35f, spdFuel(14, 40)!!, 0.001f)
        assertEquals(100f, spdFuel(45, 40))
        // Sin capacidad o sin litros no se adivina.
        assertNull(spdFuel(14, null))
        assertNull(spdFuel(14, 0))
        assertNull(spdFuel(0, 40))
    }

    @Test
    fun voltageComesInTenths() {
        assertEquals(12.3f, spdVolts(123)!!, 0.001f)
        assertNull(spdVolts(0))
        assertNull(spdVolts(321))
    }

    @Test
    fun givesOnlyCheckedSignals() {
        assertTrue(SpdCar.gives(Signal.SPEED))
        assertTrue(SpdCar.gives(Signal.FUEL))
        assertTrue(SpdCar.gives(Signal.VOLTAGE))
        assertFalse(SpdCar.gives(Signal.RPM))
    }
}
