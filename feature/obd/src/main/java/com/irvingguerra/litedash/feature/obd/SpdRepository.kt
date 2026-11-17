package com.irvingguerra.litedash.feature.obd

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SpdState { OFF, CONNECTING, ON, ERROR }

/** Datos del CAN mediante el servicio SPD, con el mismo contrato de lectura de MiniLauncher. */
class SpdRepository(context: Context, private val bus: VehicleBus) {
    private val car = SpdCarInfo.get(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = mutableSetOf<Any>()
    private var releaseJob: Job? = null
    private var freshnessJob: Job? = null
    private var active = false
    private var lastAttemptAt = 0L

    val available get() = car.available()
    var state by mutableStateOf(SpdState.OFF)
        private set
    var readable by mutableStateOf<Set<String>>(emptySet())
        private set
    var diagnostics by mutableStateOf<Map<String, String?>>(emptyMap())
        private set
    var diagnosticsAt by mutableStateOf(0L)
        private set
    var services by mutableStateOf<List<SpdService>>(emptyList())
        private set
    var doors by mutableStateOf<List<Boolean>?>(null)
        private set

    init { car.setListener { sync() } }

    fun want(owner: Any) {
        releaseJob?.cancel()
        releaseJob = null
        if (owners.add(owner) && state != SpdState.ON) connect()
    }

    fun release(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(1_000)
            if (owners.isEmpty()) disconnect()
        }
    }

    fun wake() {
        if (owners.isNotEmpty() && state != SpdState.ON) connect()
    }

    fun connect() {
        if (!available) { state = SpdState.ERROR; return }
        releaseJob?.cancel()
        if (active && state == SpdState.ON) return
        if (active && state == SpdState.ERROR) car.release()
        active = true
        state = SpdState.CONNECTING
        lastAttemptAt = SystemClock.elapsedRealtime()
        car.start()
        if (freshnessJob?.isActive != true) freshnessJob = scope.launch {
            while (active) {
                sync()
                if (state == SpdState.ERROR && owners.isNotEmpty() && SystemClock.elapsedRealtime() - lastAttemptAt >= 8_000) connect()
                delay(500)
            }
        }
    }

    fun disconnect() {
        active = false
        owners.clear()
        releaseJob?.cancel()
        freshnessJob?.cancel()
        freshnessJob = null
        car.release()
        bus.clear(Source.CAN)
        readable = emptySet()
        diagnostics = emptyMap()
        diagnosticsAt = 0
        services = emptyList()
        doors = null
        state = SpdState.OFF
    }

    private fun sync() {
        if (!active) return
        state = when (car.state()) {
            SpdCarInfo.CONNECTED -> SpdState.ON
            SpdCarInfo.CONNECTING -> SpdState.CONNECTING
            else -> SpdState.ERROR
        }
        val v = car.vehicle()
        val connected = state == SpdState.ON
        val values = mapOf(
            Signal.SPEED to if (connected) spdSpeed(v.speedKmh, v.speedAt, SystemClock.elapsedRealtime()) else null,
            Signal.VOLTAGE to v.batteryVolts,
            Signal.AMBIENT to v.outsideC.takeIf { connected },
            Signal.CONSUMPTION to spdConsumption(v.consumption).takeIf { connected },
            SpdSignals.FUEL_LITRES to v.fuelLitres.takeIf { connected },
            SpdSignals.RANGE to v.rangeKm.takeIf { connected },
            SpdSignals.ODOMETER to v.odometerKm.takeIf { connected },
            SpdSignals.AVERAGE_SPEED to v.avgSpeedKmh.takeIf { connected },
        )
        for ((signal, value) in values) bus.publish(signal, value, Source.CAN)
        readable = values.filterValues { it != null }.keys
        diagnostics = if (connected) v.spdDiagnostics.orEmpty().toMap() else emptyMap()
        diagnosticsAt = if (connected) v.spdDiagnosticsAt else 0
        services = if (connected) v.services.orEmpty().map { SpdService(it.code, it.year, it.month, it.remainingKm, it.ok) } else emptyList()
        doors = if (connected) v.doors?.let { listOf(it.frontLeft, it.frontRight, it.rearLeft, it.rearRight, it.trunk, it.hood) } else null
    }

    fun canRead(signal: String) = signal in readable

    suspend fun factoryTires(): FactoryTires {
        if (state != SpdState.ON) return FactoryTires.NotConnected
        val tyres = car.vehicle().tyresBar ?: return FactoryTires.Unsupported
        return FactoryTires.Values(List(4) { spdTyreKpa(tyres.getOrNull(it)) }, emptyList(), "SPD")
    }

    /** SPD no ofrece las operaciones de diagnóstico OBD. Nunca se inventa un informe sin fallas. */
    val lastReport: EngineReport? get() = null
    suspend fun report(): EngineReport? = null

    suspend fun describe(): String = "SPD: estado=${state.name.lowercase()}, servicio=com.spd.carinfo, " +
        "disponible=$available, señales=${readable.sorted()}, " +
        "diagnóstico=$diagnostics, consulta=$diagnosticsAt"

    companion object {
        /** Detecta la central sin leer preferencias antiguas ni abrir un adaptador OBD. */
        fun available(context: Context): Boolean = try {
            context.packageManager.getPackageInfo("com.spd.carinfo", 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
    }
}
