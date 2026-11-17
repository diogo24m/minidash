package com.irvingguerra.litedash.feature.obd

import com.irvingguerra.litedash.core.vehicle.Signal

/** Unidades del bus: conserva litros y km sin confundirlos con porcentajes ni distancias de falla. */
object SpdSignals {
    const val FUEL_LITRES = "fuel_litres"
    const val RANGE = "range"
    const val ODOMETER = "odometer"
    const val AVERAGE_SPEED = "average_speed"
}

internal fun spdConsumption(litresPer100Km: Float?): Float? =
    litresPer100Km?.takeIf { it.isFinite() && it > 0f && it <= 50f }

/** Solo el perfil BMW MINI tiene la escala de velocidad utilizada por MiniLauncher. */
internal fun spdSpeed(value: Float?, readAt: Long, now: Long): Float? =
    value?.takeIf { it.isFinite() && it in 0f..400f && readAt > 0 && now >= readAt && now - readAt <= 3_000 }

internal fun spdTyreKpa(bar: Float?): Float? = bar?.takeIf { it.isFinite() && it in 0f..6f }?.times(100f)

internal val SPD_SIGNALS = setOf(
    Signal.SPEED, Signal.VOLTAGE, Signal.AMBIENT, Signal.CONSUMPTION,
    SpdSignals.FUEL_LITRES, SpdSignals.RANGE, SpdSignals.ODOMETER, SpdSignals.AVERAGE_SPEED,
)

data class SpdService(val code: Int, val year: Int?, val month: Int?, val remainingKm: Int?, val ok: Boolean)

/** Se conserva el tipo de los informes existentes; SPD no publica DTC, VIN ni monitores OBD. */
data class EngineReport(
    val readiness: Readiness?,
    val stored: List<String>,
    val pending: List<String>,
    val permanent: List<String>,
    val vin: String?,
    val voltage: Float?,
)
