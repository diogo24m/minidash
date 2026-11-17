package com.irvingguerra.litedash

import android.content.ActivityNotFoundException
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.obd.EngineAlert
import com.irvingguerra.litedash.feature.obd.SpdSignals
import com.irvingguerra.litedash.feature.obd.SpdRepository
import com.irvingguerra.litedash.feature.obd.SpdState
import com.irvingguerra.litedash.feature.obd.engineAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Datos y diagnósticos del servicio SPD de la central. */
@Composable
internal fun Workshop(d: Dependencies, onTires: () -> Unit) {
    val spd = d.obd
    LifecycleStartEffect(spd, d.obdModule) {
        if (d.obdModule) spd.want(WORKSHOP)
        spd.wake()
        onStopOrDispose { spd.release(WORKSHOP) }
    }
    Connection(d)
    if (spd.state == SpdState.ON) {
        when (tabs(listOf(R.string.tab_sensors, R.string.tab_charts, R.string.spd_diagnostics, R.string.tab_more), "spd_workshop")) {
            0 -> {
                LiveSensors(d)
                SettingsCard(stringResource(R.string.spd_vehicle)) {
                    spd.doors?.let { doors ->
                        val names = listOf(R.string.spd_front_left, R.string.spd_front_right, R.string.spd_rear_left, R.string.spd_rear_right, R.string.spd_trunk, R.string.spd_hood)
                        for ((i, open) in doors.withIndex())
                            Label(stringResource(R.string.spd_field, stringResource(names[i]), stringResource(if (open) R.string.spd_open else R.string.spd_closed)), Typography.body)
                    }
                    Button(stringResource(R.string.tires), onTires)
                }
                if (spd.services.isNotEmpty()) SettingsCard(stringResource(R.string.spd_maintenance)) {
                    for (service in spd.services) {
                        val title = stringResource(when (service.code) { 1 -> R.string.spd_service_oil; 2 -> R.string.spd_service_inspection; 3 -> R.string.spd_service_brakes; else -> R.string.spd_maintenance })
                        val date = if (service.month != null && service.year != null) String.format(currentLocale(), "%02d/%04d", service.month, service.year) else "—"
                        val left = service.remainingKm?.let { sensorText(SpdSignals.RANGE, it.toFloat(), d.prefs.units, currentLocale()) } ?: "—"
                        Label(title, Typography.subtitle)
                        Label(stringResource(R.string.spd_service_details, date, left, stringResource(if (service.ok) R.string.spd_service_ok else R.string.spd_service_unknown)), Typography.body)
                    }
                }
            }
            1 -> LiveCharts(d)
            2 -> SettingsCard(stringResource(R.string.spd_diagnostics), stringResource(R.string.spd_diagnostics_help)) {
                var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
                LaunchedEffect(Unit) { while (true) { delay(1_000); now = android.os.SystemClock.elapsedRealtime() } }
                if (spd.diagnosticsAt == 0L) Label(stringResource(R.string.obd_reading), Typography.body)
                else if (now - spd.diagnosticsAt > 30_000) Label(stringResource(R.string.spd_stale), Typography.body, LocalColors.current.warning)
                for ((key, value) in spd.diagnostics) {
                    val name = stringResource(when (key) {
                        "spd_check" -> R.string.spd_check
                        "spd_acc" -> R.string.spd_acc
                        "spd_brake" -> R.string.spd_brake
                        "spd_handbrake" -> R.string.spd_handbrake
                        "spd_parking" -> R.string.spd_parking
                        "spd_gear" -> R.string.spd_gear
                        "spd_rpm_100004" -> R.string.spd_rpm_100004
                        "spd_rpm_100042" -> R.string.spd_rpm_100042
                        "spd_rpm_120340" -> R.string.spd_rpm_120340
                        "spd_speed" -> R.string.sensor_speed
                        "spd_lights" -> R.string.spd_lights
                        "spd_brightness" -> R.string.spd_brightness
                        else -> R.string.spd_oil
                    })
                    Label(name, Typography.subtitle)
                    Label(value ?: "—", Typography.body)
                }
            }
            else -> { AccelTimerCard(d); TripsCard(d) }
        }
    } else TripsCard(d)
}

/** La llave con que el Taller quiere el escáner ([SpdRepository.want]). */
private val WORKSHOP = Any()

/** Una alerta ya resuelta se olvida tras esto (un dato perdido no la "resuelve"). */
private const val ALERT_CLEAR_MS = 30_000L

/**
 * Lo que avisó el motor y lo que se descartó con "Entendido". Vive en el inicio (no en el aviso):
 * abrir Ajustes o acomodar quita el aviso de la pantalla, pero no lo vuelve a mostrar al regresar.
 */
@Stable
class EngineAlertState {
    var shown by mutableStateOf<EngineAlert?>(null)
    var dismissed by mutableStateOf<EngineAlert?>(null)
}

/**
 * Aviso en el inicio si el motor se calienta de más o la batería no carga. Con un
 * adaptador guardado y las alertas activas, conecta el OBD aunque no haya medidores.
 */
@Composable
fun EngineAlertBanner(d: Dependencies, state: EngineAlertState) {
    if (rememberObd(d, d.prefs.engineAlerts)?.state != SpdState.ON) return
    val coolant = d.bus.watch(Signal.COOLANT)
    val voltage = d.bus.watch(Signal.VOLTAGE)
    val alert = engineAlert(coolant, voltage, d.bus.watch(Signal.RPM))
    var shown by state::shown
    var dismissed by state::dismissed
    // Una falla tiene que durar ([EngineAlert.holdMs]) antes de avisar; y para volver a
    // avisar tras "Entendido", tiene que haberse resuelto un rato, no solo perder un dato.
    LaunchedEffect(alert) {
        if (alert == null) {
            delay(ALERT_CLEAR_MS)
            shown = null
            dismissed = null
        } else {
            delay(alert.holdMs)
            shown = alert
        }
    }
    val a = shown?.takeIf { it != dismissed } ?: return
    val locale = currentLocale()
    val units = d.prefs.units
    val volts = voltage?.let { stringResource(R.string.value_with_unit, String.format(locale, "%.1f", it), stringResource(R.string.unit_volts)) } ?: "—"
    val text = when (a) {
        EngineAlert.HOT -> stringResource(R.string.alert_hot, coolant?.let { sensorText(Signal.COOLANT, it, units, locale) } ?: "—")
        EngineAlert.NOT_CHARGING -> stringResource(R.string.alert_not_charging, volts)
        EngineAlert.OVERCHARGING -> stringResource(R.string.alert_overcharging, volts)
        EngineAlert.LOW_BATTERY -> stringResource(R.string.alert_low_battery, volts)
    }
    Notice(text, Icons.warning, LocalColors.current.danger) {
        Button(stringResource(R.string.alert_ok), { dismissed = a }, primary = true)
    }
}

/** Estado de la conexión integrada; SPD no necesita emparejamiento ni permisos Bluetooth. */
@Composable
private fun Connection(d: Dependencies) {
    val spd = d.obd
    SettingsCard(stringResource(R.string.spd_connection), stringResource(R.string.spd_connection_help)) {
        SwitchRow(stringResource(R.string.module_obd), d.obdModule, { d.obdModule = it })
        val status = spdConnectionText(spd.available, spd.state)
        Label(stringResource(status), Typography.body)
        if (spd.available && d.obdModule && spd.state != SpdState.ON && spd.state != SpdState.CONNECTING)
            Button(stringResource(R.string.obd_connect), { spd.connect() }, primary = true)
    }
}

internal fun spdConnectionText(available: Boolean, state: SpdState): Int =
    if (!available) R.string.spd_unavailable else when (state) {
        SpdState.ON -> R.string.spd_connected
        SpdState.CONNECTING -> R.string.obd_connecting_short
        SpdState.ERROR -> R.string.spd_failed
        SpdState.OFF -> R.string.obd_off
    }

/**
 * Un permiso que se pide al tocar. [denied]: ya se negó; Android puede no volver a preguntar, así que junto a
 * «Permitir» se ofrece abrir sus ajustes ([PermissionButtons]).
 */
internal class PermissionAsk(private val deniedState: MutableState<Boolean>, val ask: () -> Unit) {
    var denied by deniedState
}

@Composable
internal fun rememberPermissionAsk(permission: String, onResult: (Boolean) -> Unit = {}): PermissionAsk {
    val denied = rememberSaveable { mutableStateOf(false) }
    val result by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied.value = !ok
        result(ok)
    }
    return remember(launcher, permission) { PermissionAsk(denied) { launcher.launch(permission) } }
}

/** «Permitir» ([allow]) y, ya negado, «Permisos» (los ajustes de LiteDash en Android). Van en la fila de quien llama. */
@Composable
internal fun PermissionButtons(p: PermissionAsk, allow: String) {
    val context = LocalContext.current
    Button(allow, p.ask, primary = true)
    if (p.denied) Button(stringResource(R.string.local_network_settings), { openAppSettings(context) })
}

/** Pide prender el Bluetooth con el diálogo de Android; la pantalla que no lo tiene abre sus ajustes de Bluetooth. */
internal fun requestBluetoothOn(context: Context, launcher: ManagedActivityResultLauncher<Intent, ActivityResult>) {
    try {
        launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    } catch (_: ActivityNotFoundException) {
        openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}

/** Si el Bluetooth de la pantalla está encendido (null = no tiene); se actualiza al cambiar. */
@Composable
internal fun rememberBluetoothOn(): Boolean? {
    val context = LocalContext.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter } ?: return null
    var on by remember { mutableStateOf(adapter.isEnabled) }
    LifecycleStartEffect(adapter) {
        on = adapter.isEnabled
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                on = adapter.isEnabled
            }
        }
        // Es un aviso del sistema: el receptor debe aceptar avisos de fuera de la app.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onStopOrDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return on
}

private val SENSOR_ORDER = listOf(Signal.RPM, Signal.COOLANT, Signal.VOLTAGE, Signal.CONSUMPTION, Signal.SPEED, Signal.LOAD, Signal.OIL, Signal.BOOST)

/** Los sensores que da este auto, en vivo, en tarjetas con su línea de 90 s (solo se leen mientras se ven). */
@Composable
private fun LiveSensors(d: Dependencies) {
    val locale = currentLocale()
    val units = d.prefs.units
    // Los que da el auto, los calculados (consumo, turbo) y el voltaje (lo mide el adaptador si el auto no).
    val readable = readableSignals(d)
    val signals = remember(readable) { readable.sortedBy { SENSOR_ORDER.indexOf(it).let { i -> if (i < 0) SENSOR_ORDER.size else i } } }
    SettingsCard(stringResource(R.string.obd_live)) {
        TileGrid(signals, minWidth = 170.dp, maxColumns = 4) { signal, modifier -> SensorTile(d, signal, units, locale, modifier) }
    }
}

/** Una tarjeta por sensor: cada dato nuevo solo vuelve a dibujar la suya. */
@Composable
private fun SensorTile(d: Dependencies, signal: String, units: UnitChoice, locale: java.util.Locale, modifier: Modifier) {
    val value = d.bus.watch(signal)
    val series = rememberSeries(d, signal)
    StatTile(
        name = stringResource(sensorName(signal)),
        value = value?.let { sensorText(signal, it, units, locale) } ?: "—",
        modifier = modifier,
        series = series,
        seriesColor = LocalColors.current.accent,
    )
}

internal fun sensorName(signal: String) = when (signal) {
    SpdSignals.FUEL_LITRES -> R.string.spd_fuel_litres
    SpdSignals.RANGE -> R.string.spd_range
    SpdSignals.ODOMETER -> R.string.spd_odometer
    SpdSignals.AVERAGE_SPEED -> R.string.spd_average_speed
    Signal.SPEED -> R.string.sensor_speed
    Signal.RPM -> R.string.sensor_rpm
    Signal.LOAD -> R.string.sensor_load
    Signal.MAP -> R.string.sensor_map
    Signal.MAF -> R.string.sensor_maf
    Signal.COOLANT -> R.string.sensor_coolant
    Signal.INTAKE -> R.string.sensor_intake
    Signal.FUEL -> R.string.sensor_fuel
    Signal.VOLTAGE -> R.string.sensor_voltage
    Signal.BARO -> R.string.sensor_baro
    Signal.CONSUMPTION -> R.string.sensor_consumption
    Signal.BOOST -> R.string.sensor_boost
    Signal.FUEL_TRIM_SHORT -> R.string.sensor_fuel_trim_short
    Signal.FUEL_TRIM_LONG -> R.string.sensor_fuel_trim_long
    Signal.FUEL_PRESSURE -> R.string.sensor_fuel_pressure
    Signal.TIMING -> R.string.sensor_timing
    Signal.THROTTLE -> R.string.sensor_throttle
    Signal.RUN_TIME -> R.string.sensor_run_time
    Signal.DISTANCE_MIL -> R.string.sensor_distance_mil
    Signal.OIL -> R.string.sensor_oil
    Signal.FUEL_RATE -> R.string.sensor_fuel_rate
    else -> R.string.sensor_ambient
}

/**
 * El valor con su unidad; en millas y °F donde se usan (como el velocímetro). Lo que ya
 * es medidor se escribe igual que en su tarjeta ([GaugeSpecs]); aquí solo va lo demás.
 */
@Composable
fun sensorText(signal: String, v: Float, units: UnitChoice, locale: java.util.Locale): String {
    @Composable fun with(number: String, unit: Int) = stringResource(R.string.value_with_unit, number, stringResource(unit))
    gaugeOf(signal)?.let { spec -> return with(gaugeText(spec, v, units, locale), spec.unit(units)) }
    return when (signal) {
        SpdSignals.FUEL_LITRES -> with(String.format(locale, "%.0f", v), R.string.unit_litres)
        SpdSignals.RANGE, SpdSignals.ODOMETER -> if (units.miles) with(String.format(locale, "%.0f", v.toMiles(true)), R.string.unit_mi) else with(String.format(locale, "%.0f", v), R.string.unit_km)
        Signal.SPEED, SpdSignals.AVERAGE_SPEED -> if (units.miles) with(String.format(locale, "%.0f", v.toMiles(true)), R.string.unit_mph) else with(String.format(locale, "%.0f", v), R.string.unit_kmh)
        Signal.LOAD -> with(String.format(locale, "%.0f", v), R.string.unit_percent)
        Signal.MAP, Signal.BARO, Signal.FUEL_PRESSURE -> with(String.format(locale, "%.0f", v), R.string.unit_kpa)
        Signal.MAF -> with(String.format(locale, "%.1f", v), R.string.unit_gs)
        Signal.FUEL_TRIM_SHORT, Signal.FUEL_TRIM_LONG -> with(String.format(locale, "%+.1f", v), R.string.unit_percent)
        Signal.TIMING -> String.format(locale, "%.1f°", v)
        // Horas y minutos (h:mm).
        Signal.RUN_TIME -> (v / 60).toInt().let { m -> String.format(locale, "%d:%02d", m / 60, m % 60) }
        Signal.DISTANCE_MIL -> if (units.miles) with(String.format(locale, "%.0f", v.toMiles(true)), R.string.unit_mi) else with(String.format(locale, "%.0f", v), R.string.unit_km)
        // Admisión y ambiente.
        else -> tempText(v, units.fahrenheit)
    }
}
