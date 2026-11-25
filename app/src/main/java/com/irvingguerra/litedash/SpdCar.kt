package com.irvingguerra.litedash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.ContentObserver
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * La caja CAN de la pantalla (módulo «Caja CAN de la pantalla», apagado de fábrica) en las pantallas con la pila SPD
 * (`com.spd.carinfo`, p. ej. Qualcomm msm8953): su servicio ya lee la caja CAN y reparte los datos por Binder a quien
 * se suscribe. LiteDash le habla a mano con los códigos de transacción de la versión comprobada ([VERIFIED_VERSIONS]):
 * no carga nada de su app. Solo le llama a suscribirse, darse de baja y dos lecturas ([CALLS]); nunca a sus setters,
 * que cambian ajustes del auto. Antes de conectar revisa que su servicio sea una app del sistema, de una versión
 * comprobada, y que el perfil CAN sea uno comprobado con el auto ([PROFILES]); si algo no cuadra, no le habla.
 *
 * Sin leer cada rato: cada señal se pide solo mientras una pieza la muestra ([rememberSpd]) y después llega sola. La
 * velocidad y la gasolina se leen una vez al empezar y luego con los avisos de su servicio; el voltaje es un ajuste
 * del sistema que escribe la pantalla y se escucha con un observador. Todo va al bus como [Source.CAN].
 */
class SpdCar(private val context: Context, private val bus: VehicleBus, private val gpsSpeed: () -> Float?) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    /** Cuántas piezas muestran cada señal. Esto y lo que sigue, solo en el hilo principal. */
    private val users = HashMap<String, Int>()

    /** Si la pantalla está comprobada (null = aún no se revisa). Es estado: las piezas cambian al saberlo. */
    var usable by mutableStateOf<Boolean?>(null)
        private set

    /** El hilo de la caja CAN (llamadas a su servicio y lectura de ajustes); solo existe mientras algo se muestra. */
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    /** La pantalla ya se revisó en esta vuelta y está comprobada: se puede conectar y leer. */
    private var ready = false
    private var bound = false
    private var voltageObserver: ContentObserver? = null
    private var gpsWatch: Job? = null

    /** La última velocidad de la caja CAN, antes de ver si se le cree ([spdSpeedTrusted]). */
    private var canSpeed: Float? = null

    // Del hilo de la caja CAN.
    @Volatile private var service: IBinder? = null
    private val subscribed = HashSet<String>()
    @Volatile private var tank: Int? = null

    // Para el reporte.
    @Volatile private var problem: String? = null
    private val events = AtomicInteger()
    @Volatile private var lastEventAt = 0L

    /** Una pieza empieza a mostrar [signal]: la primera conecta (en una pantalla comprobada) y pide sus avisos. */
    fun acquire(signal: String) {
        if (!gives(signal)) return
        val n = users[signal] ?: 0
        users[signal] = n + 1
        if (n > 0) return
        if (thread == null) start() else if (ready) begin(signal)
    }

    /** Ya no se muestra [signal] en esa pieza: la última se da de baja de sus avisos; sin ninguna, se desconecta. */
    fun release(signal: String) {
        val n = users[signal] ?: return
        if (n > 1) {
            users[signal] = n - 1
            return
        }
        users.remove(signal)
        if (users.isEmpty()) shutdown() else end(signal)
    }

    /** Revisa la pantalla en su hilo (pregunta al sistema) y, si está comprobada, conecta. */
    private fun start() {
        val t = HandlerThread("spd-car", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val w = Handler(t.looper)
        thread = t
        worker = w
        w.post {
            val check = check(context)
            main.post { if (worker === w) checked(check) }
        }
    }

    private fun checked(check: SpdCheck?) {
        usable = check?.ok == true
        if (check?.ok != true) return
        ready = true
        bind()
        for (signal in users.keys.toList()) begin(signal)
    }

    /** Empieza a dar [signal]: lo que va por su servicio se pide al conectar (o ya, si está conectado). */
    private fun begin(signal: String) {
        when (signal) {
            Signal.VOLTAGE -> watchVoltage()
            Signal.SPEED -> watchGps()
        }
        worker?.post { subscribe(signal) }
    }

    /** Deja de dar [signal] (otras siguen). */
    private fun end(signal: String) {
        when (signal) {
            Signal.VOLTAGE -> unwatchVoltage()
            Signal.SPEED -> {
                unwatchGps()
                canSpeed = null
            }
        }
        worker?.post { unsubscribe(signal) }
        bus.publish(signal, null, Source.CAN)
    }

    /** Ya nada se muestra (o hay que volver a empezar): se da de baja de todo, desconecta y quita lo publicado. */
    private fun shutdown() {
        unwatchVoltage()
        unwatchGps()
        canSpeed = null
        ready = false
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        val w = worker
        val t = thread
        worker = null
        thread = null
        w?.post {
            for (signal in subscribed.toList()) unsubscribe(signal)
            service = null
        }
        t?.quitSafely()
        for (signal in SIGNALS) bus.publish(signal, null, Source.CAN)
    }

    private fun bind() {
        bound = runCatching { context.bindService(Intent().setClassName(PACKAGE, SERVICE), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { problem = "no se pudo conectar (${it.javaClass.simpleName})" }
            .getOrDefault(false)
        if (!bound) {
            if (problem == null) problem = "Android no dejó conectar"
            runCatching { context.unbindService(connection) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binder ?: return
            val signals = users.keys.toList()
            worker?.post { connected(binder, signals) }
        }

        // Su proceso se cayó: lo suscrito se fue con él. Android vuelve a conectar solo cuando arranque de nuevo.
        override fun onServiceDisconnected(name: ComponentName?) {
            worker?.post {
                service = null
                subscribed.clear()
            }
            canSpeed = null
            for (signal in CATEGORIES.keys) bus.publish(signal, null, Source.CAN)
        }

        // Su app se actualizó o se apagó: Android ya no vuelve a conectar solo. Se revisa otra vez (quizá es otra versión).
        override fun onBindingDied(name: ComponentName?) {
            if (thread == null) return
            shutdown()
            start()
        }

        override fun onNullBinding(name: ComponentName?) {
            problem = "su servicio no da su interfaz"
        }
    }

    /** En el hilo de la caja CAN: es su servicio (no otro con el mismo nombre de clase) y se piden las señales [signals]. */
    private fun connected(binder: IBinder, signals: List<String>) {
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
        if (descriptor != DESCRIPTOR) {
            problem = "interfaz inesperada: $descriptor"
            return
        }
        problem = null
        service = binder
        subscribed.clear()
        for (signal in signals) subscribe(signal)
    }

    /** En el hilo de la caja CAN: los avisos de la categoría de [signal] y su valor de ahora (sin esperar a que cambie). */
    private fun subscribe(signal: String) {
        val binder = service ?: return
        val category = CATEGORIES[signal] ?: return
        if (!subscribed.add(signal)) return
        if (!subscription(binder, REGISTER, category)) problem = "no aceptó los avisos de $category"
        when (signal) {
            Signal.SPEED -> getFloat(binder, CURRENT_SPEED)?.let { kmh -> main.post { onSpeed(kmh) } }
            Signal.FUEL -> {
                tank = setting(TANK_KEY)
                getInt(binder, REMAIN_FUEL)?.let { liters -> main.post { onFuel(liters) } }
            }
        }
    }

    /** En el hilo de la caja CAN. */
    private fun unsubscribe(signal: String) {
        if (!subscribed.remove(signal)) return
        val binder = service ?: return
        CATEGORIES[signal]?.let { subscription(binder, UNREGISTER, it) }
    }

    private fun onSpeed(kmh: Float) {
        if (!ready || Signal.SPEED !in users) return
        canSpeed = spdSpeed(kmh)
        publishSpeed()
    }

    private fun publishSpeed() =
        bus.publish(Signal.SPEED, canSpeed?.takeIf { spdSpeedTrusted(it, gpsSpeed()) }, Source.CAN)

    private fun onFuel(liters: Int) {
        if (!ready || Signal.FUEL !in users) return
        bus.publish(Signal.FUEL, spdFuel(liters, tank), Source.CAN)
    }

    /**
     * Mientras se muestra la velocidad, cada lectura del GPS vuelve a decidir si se le cree a la caja CAN: su 0 puede
     * ser "sin dato", y sin cambios no llega aviso. Sin GPS no hace nada.
     */
    private fun watchGps() {
        if (gpsWatch != null) return
        gpsWatch = scope.launch { snapshotFlow { gpsSpeed() }.collect { publishSpeed() } }
    }

    private fun unwatchGps() {
        gpsWatch?.cancel()
        gpsWatch = null
    }

    /** El voltaje: el ajuste que escribe la pantalla, escuchado (lo avisa al cambiar) y leído una vez al empezar. */
    private fun watchVoltage() {
        val w = worker ?: return
        if (voltageObserver != null) return
        val observer = object : ContentObserver(w) {
            override fun onChange(selfChange: Boolean) = readVoltage()
        }
        runCatching { context.contentResolver.registerContentObserver(Settings.System.getUriFor(VOLTAGE_KEY), false, observer) }
            .onSuccess { voltageObserver = observer }
        w.post { readVoltage() }
    }

    private fun unwatchVoltage() {
        voltageObserver?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        voltageObserver = null
    }

    /** En el hilo de la caja CAN. */
    private fun readVoltage() {
        val volts = setting(VOLTAGE_KEY)?.let(::spdVolts)
        counted()
        main.post { if (ready && Signal.VOLTAGE in users) bus.publish(Signal.VOLTAGE, volts, Source.CAN) }
    }

    /**
     * Los avisos de su servicio. Los manda esperando respuesta (no son de una vía): se responde enseguida y lo útil
     * pasa al hilo principal. Solo se leen la velocidad y la gasolina; lo demás se recibe y se ignora.
     */
    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in CALLBACK_INT..CALLBACK_BUNDLE) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(CALLBACK)
            } catch (_: SecurityException) {
                return false
            }
            when (code) {
                CALLBACK_INT -> {
                    val id = data.readInt()
                    val value = data.readInt()
                    if (data.readInt() == 0 && id == REMAIN_FUEL) {
                        counted()
                        main.post { onFuel(value) }
                    }
                }
                CALLBACK_FLOAT -> {
                    val id = data.readInt()
                    val value = data.readFloat()
                    if (data.readInt() == 0 && id == CURRENT_SPEED) {
                        counted()
                        main.post { onSpeed(value) }
                    }
                }
            }
            reply?.writeNoException()
            return true
        }
    }

    private fun counted() {
        events.incrementAndGet()
        lastEventAt = SystemClock.elapsedRealtime()
    }

    /** `getInt(id, 0)`, como lo pide su AIDL (el 2.º argumento siempre es 0). En el hilo de la caja CAN. */
    private fun getInt(binder: IBinder, id: Int): Int? = call(binder, GET_INT, {
        writeInt(id)
        writeInt(0)
    }) { readInt() }

    /** `getFloat(id, 0)`; null si no es un número. */
    private fun getFloat(binder: IBinder, id: Int): Float? = call(binder, GET_FLOAT, {
        writeInt(id)
        writeInt(0)
    }) { readFloat() }?.takeIf { it.isFinite() }

    /** `registerCallback(paquete, [categoría], aviso)` o `unregisterCallback(paquete, [categoría])`: su servicio las une por paquete y categorías. */
    private fun subscription(binder: IBinder, code: Int, category: String): Boolean = call(binder, code, {
        writeString(context.packageName)
        writeStringArray(arrayOf(category))
        if (code == REGISTER) writeStrongBinder(callback)
    }) { true } == true

    /** Una llamada a su servicio; solo de [CALLS] (cualquier otro código, ni se manda). null si no respondió. */
    private inline fun <T> call(binder: IBinder, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T? {
        if (code !in CALLS) return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.read()
        } catch (e: Exception) {
            // Se cayó su proceso (DeadObjectException) o respondió con un error: se queda sin ese dato.
            problem = "falló la llamada $code (${e.javaClass.simpleName})"
            Log.w(TAG, "falló la llamada $code al servicio SPD", e)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun setting(key: String): Int? = setting(context, key)

    /** Para el reporte, en memoria: si está comprobada, conectada, qué muestra y cuántos avisos llegaron. */
    fun describe(): String = buildString {
        append("Caja CAN: ")
        append(
            when (usable) {
                null -> "sin revisar"
                true -> "comprobada"
                false -> "sin comprobar"
            },
        )
        append(", ").append(if (service != null) "conectada" else "sin conectar")
        append(", muestra ").append(users.keys.sorted().joinToString().ifEmpty { "nada" })
        append(", ${events.get()} avisos")
        if (lastEventAt > 0L) append(" (el último hace ${(SystemClock.elapsedRealtime() - lastEventAt) / 1000} s)")
        problem?.let { append(", falla: ").append(it) }
    }

    companion object {
        private const val TAG = "SpdCar"
        private const val PACKAGE = "com.spd.carinfo"
        private const val SERVICE = "com.spd.carinfo.service.CarInfoService"
        private const val DESCRIPTOR = "com.spd.carinfo.ICarInfoAidlInterface"
        private const val CALLBACK = "com.spd.carinfo.ICarInfoAidlCallback"

        /**
         * Las versiones de su servicio (`versionCode`) cuyos códigos de transacción se comprobaron en su `Stub`
         * (`TRANSACTION_*`) leyendo el APK, sin correrlo: 25383 (`20260901153624`), la de un MINI. Otra versión podría
         * moverlos y hacer que una lectura llame a un setter: con ella no se llama nada y el reporte dice cuál es.
         */
        internal val VERIFIED_VERSIONS = setOf(25_383L)

        // Códigos de transacción de ICarInfoAidlInterface en esas versiones. Del 3 al 8 son enterSource, exitSource y
        // los setters (setInt, setFloat, setString, setBundle): nunca se llaman.
        internal const val REGISTER = 1 // registerCallback(String paquete, String[] categorías, ICarInfoAidlCallback)
        internal const val UNREGISTER = 2 // unregisterCallback(String paquete, String[] categorías)
        internal const val GET_INT = 9 // int getInt(int id, int arg)
        internal const val GET_FLOAT = 10 // float getFloat(int id, int arg)

        /** Lo único que se le llama a su servicio. */
        internal val CALLS = setOf(REGISTER, UNREGISTER, GET_INT, GET_FLOAT)

        // Los avisos de ICarInfoAidlCallback, todos (id, valor, arg): 1 entero, 2 decimal, 3 texto y 4 Bundle.
        private const val CALLBACK_INT = 1
        private const val CALLBACK_FLOAT = 2
        private const val CALLBACK_BUNDLE = 4

        // Propiedades SPD (no son códigos de Binder).
        private const val CURRENT_SPEED = 140062 // ReverseAndAVM.CURRENT_SPEED, km/h
        private const val REMAIN_FUEL = 100045 // Instruments.REMAIN_FUEL, litros (en otros perfiles es otra cosa)

        /** La categoría de avisos de cada señal que va por su servicio (el voltaje no: es un ajuste del sistema). */
        private val CATEGORIES = mapOf(Signal.SPEED to "ReverseAndAVM", Signal.FUEL to "Instruments")

        // Ajustes del sistema que escribe la pila SPD.
        private const val PROFILE_KEY = "SETTING_FACTORY_CAR" // el perfil CAN
        private const val VOLTAGE_KEY = "SETTING_CURRENT_VOLTAGE" // décimas de volt
        private const val TANK_KEY = "SETTING_FUEL_TANK_CAPACITY" // litros

        /** Perfiles CAN comprobados con el auto: zhongmayitong → BMW → BMW MINI. */
        internal val PROFILES = setOf(135_725_059)

        private val SIGNALS = setOf(Signal.SPEED, Signal.FUEL, Signal.VOLTAGE)

        /** Lo que da la caja CAN (sin averiguar si esta pantalla la tiene). */
        fun gives(signal: String) = signal in SIGNALS

        /** Si esta pantalla tiene la caja CAN comprobada: solo entonces se ofrece el módulo. Pregunta al sistema: fuera del hilo principal. */
        fun supported(context: Context) = check(context)?.ok == true

        /**
         * Para el reporte: la versión de su servicio y el perfil CAN, también sin comprobar (así se aprenden otras
         * pantallas y autos); null si no es una pantalla SPD. Pregunta al sistema: fuera del hilo principal.
         */
        fun describeScreen(context: Context): String? = check(context)?.let { "Caja CAN (SPD): ${it.describe()}" }

        private fun check(context: Context): SpdCheck? {
            val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull() ?: return null
            val system = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            return SpdCheck(system, versionOf(info), setting(context, PROFILE_KEY))
        }

        @Suppress("DEPRECATION")
        private fun versionOf(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

        private fun setting(context: Context, key: String): Int? =
            runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()
    }
}

/** Lo que se revisa de una pantalla SPD antes de hablarle a su servicio: que sea del sistema, su versión y el perfil CAN. */
internal data class SpdCheck(val system: Boolean, val version: Long, val profile: Int?) {
    val ok get() = system && version in SpdCar.VERIFIED_VERSIONS && profile in SpdCar.PROFILES

    fun describe(): String {
        val service = if (version in SpdCar.VERIFIED_VERSIONS) "comprobado" else "sin comprobar"
        val car = if (profile in SpdCar.PROFILES) "comprobado" else "sin comprobar"
        return "servicio $version $service${if (system) "" else " (no es del sistema)"} · perfil ${profile ?: "?"} $car"
    }
}

/** km/h de `CURRENT_SPEED`; null si no es creíble (0 sí vale: parado). */
internal fun spdSpeed(kmh: Float): Float? = kmh.takeIf { it in 0f..400f }

/**
 * Si la velocidad de la caja CAN ([can]) se cree junto a la del GPS ([gps], null sin él). Sin dato su servicio también
 * responde 0: un 0 mientras el GPS dice que vas andando no es "parado", y entonces gana el GPS.
 */
internal fun spdSpeedTrusted(can: Float, gps: Float?): Boolean = can > 0f || gps == null || gps <= SPD_STILL_GPS_KMH

/** Hasta aquí el GPS puede marcar algo con el auto parado (se mueve solo unos metros). */
private const val SPD_STILL_GPS_KMH = 10f

/** El % del tanque con los litros que quedan ([liters]) y su capacidad ([tank]); null si alguno no es creíble. */
internal fun spdFuel(liters: Int, tank: Int?): Float? =
    if (liters in 1..150 && tank != null && tank > 0) minOf(liters * 100f / tank, 100f) else null

/** Volts de `SETTING_CURRENT_VOLTAGE`, en décimas (123 = 12.3 V); null si no es creíble. */
internal fun spdVolts(tenths: Int): Float? = if (tenths in 1..320) tenths / 10f else null

/**
 * La caja CAN para una pieza que muestra [signal]: con su módulo prendido la lee mientras la pieza se ve (sin nada
 * encima) y LiteDash está al frente. null con el módulo apagado o si la caja no da esa señal: entonces no crea nada.
 */
@Composable
fun rememberSpd(d: Dependencies, signal: String): SpdCar? {
    if (!d.spdModule || !SpdCar.gives(signal)) return null
    val spd = d.spd
    if (!LocalHomeCovered.current) {
        LifecycleStartEffect(spd, signal) {
            spd.acquire(signal)
            onStopOrDispose { spd.release(signal) }
        }
    }
    return spd
}
