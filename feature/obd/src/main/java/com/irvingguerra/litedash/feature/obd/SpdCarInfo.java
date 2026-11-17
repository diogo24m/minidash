package com.irvingguerra.litedash.feature.obd;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datos del carro en las pantallas SPD con el perfil CAN «BMW MINI». Solo lectura: se conecta al servicio del
 * fabricante (com.spd.carinfo) y, mientras LiteDash está a la vista, le pide unos pocos valores: puertas y
 * temperatura exterior y velocidad cada segundo, neumáticos cada minuto y lo demás cada 10 s. Lee también la tensión cada
 * segundo desde Settings.System, donde la publica la central. Además se suscribe a sus avisos
 * de puertas y velocidad, que llegan al momento. Los campos SPD pendientes de validar se consultan cada 10 s y se guardan
 * como valores brutos para los diagnósticos. Solo usa lecturas (getInt, getFloat y getBundle) y la suscripción
 * (registerCallback y unregisterCallback), con los códigos que trae el propio servicio; nunca llama a nada que
 * cambie el carro. Adaptado de CarInfo.java de MiniLauncher; contrato e interpretación documentados en docs/SPD.md.
 */
final class SpdCarInfo {

    interface Listener { void onCarInfo(); }

    static final int NO_SPD = 0, CONNECTING = 1, CONNECTED = 2, FAILED = 3;

    private static final String PKG = "com.spd.carinfo";
    private static final String SERVICE = "com.spd.carinfo.service.CarInfoService";
    private static final String DESCRIPTOR = "com.spd.carinfo.ICarInfoAidlInterface";
    private static final String CALLBACK = "com.spd.carinfo.ICarInfoAidlCallback";
    private static final String CURRENT_VOLTAGE = "SETTING_CURRENT_VOLTAGE";

    // propiedades del servicio (id del fabricante)
    private static final int MODEL = 170004, FUEL = 100045, RANGE = 100011, ODOMETER = 100013, OUTSIDE = 30023,
            DOORS = 50001, UNITS = 120283, TRIP = 120287, TYRES = 120288, SERVICES = 120290, SPEED = 140062;
    /** Perfil CAN zhongmayitong → BMW → BMW MINI. En otros perfiles el 100045 es otra cosa. */
    private static final int BMW_MINI = 135725059;

    /**
     * Categorías con aviso al momento. Las puertas llegan por aviso (comprobado en el carro); el ordenador de a
     * bordo no, por eso lo demás se sigue leyendo cada cierto tiempo.
     */
    private static final String[] PUSH = { "Doors", "ReverseAndAVM" };

    private static final long TICK_MS = 1000;
    private static final int SLOW_EVERY = 10;   // lo que cambia despacio cada 10 vueltas (10 s); el resto en cada vuelta
    private static final int TYRES_EVERY = 60;  // neumáticos cada minuto

    private static SpdCarInfo instance;

    static synchronized SpdCarInfo get(Context c) {
        if (instance == null) instance = new SpdCarInfo(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean installed;
    private volatile HandlerThread thread;
    private volatile Handler worker;     // también lo usan los avisos, que llegan por hilos de Binder
    private ServiceConnection conn;
    private volatile IBinder binder;
    private volatile int state;
    private volatile SpdVehicle data = new SpdVehicle();
    private volatile boolean running;
    private volatile Listener listener;
    private volatile int codeChanged;    // código de onCarInfoBundleChanged (0 = sin avisos)
    private volatile int codeSpeedChanged; // onCarInfoFloatChanged, opcional

    // solo en el hilo del CAN
    private int codeInt, codeFloat, codeBundle; // códigos de los getters, leídos de la clase del propio servicio
    private int codeRegister, codeUnregister;
    private IBinder subscribed;          // servicio al que se pidieron los avisos (null = sin suscripción)
    private int tick, model;
    private long speedPushAt;
    private Integer distUnit, energyUnit; // unidades que elige el carro (0 = km, 0 = L/100 km)

    private SpdCarInfo(Context app) {
        this.app = app;
        boolean ok;
        try { app.getPackageManager().getPackageInfo(PKG, 0); ok = true; } catch (Throwable e) { ok = false; }
        installed = ok;
        state = ok ? CONNECTING : NO_SPD;
    }

    /** ¿Es una pantalla SPD con el servicio de datos del carro? */
    boolean available() { return installed; }

    int state() { return state; }

    /** Últimos datos (no se modifican una vez publicados). */
    SpdVehicle vehicle() { return data; }

    void setListener(Listener l) { listener = l; }

    /** Al volver a la pantalla: conecta si hace falta y empieza a leer. */
    void start() {
        if (!installed) return;
        running = true;
        if (thread == null) {
            thread = new HandlerThread("SpdCarInfo", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        if (conn == null) bind();
        worker.removeCallbacks(poll);
        worker.post(syncPush);
        worker.post(poll);
    }

    /** Al salir de la pantalla: deja de leer y se da de baja de los avisos (la conexión sigue abierta). */
    void stop() {
        running = false;
        Handler w = worker;
        if (w != null) {
            w.removeCallbacks(poll);
            w.post(syncPush);
        }
    }

    /** Al cerrar la app. */
    void release() {
        running = false;
        final Handler oldWorker = worker;
        final HandlerThread oldThread = thread;
        final IBinder oldSubscription = subscribed;
        final int unregister = codeUnregister;
        subscribed = null;
        worker = null;
        thread = null;
        if (oldWorker != null) {
            oldWorker.removeCallbacksAndMessages(null);
            oldWorker.post(new Runnable() { public void run() {
                if (oldSubscription != null && unregister != 0) push(oldSubscription, unregister, false);
                if (oldThread != null) oldThread.quit();
            } });
        }
        if (conn != null) {
            try { app.unbindService(conn); } catch (Throwable ignored) { }
            conn = null;
        }
        binder = null;
        data = new SpdVehicle();
        setState(installed ? CONNECTING : NO_SPD);
    }

    private void bind() {
        ServiceConnection c = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, final IBinder b) {
                if (conn != this) return;
                final ServiceConnection current = this;
                Handler w = worker;
                if (w != null) w.post(new Runnable() { public void run() {
                    if (conn == current && running) connected(b);
                } });
            }
            public void onServiceDisconnected(ComponentName n) {
                if (conn != this) return;
                binder = null;   // Android vuelve a conectar solo cuando el servicio arranca de nuevo
                data = new SpdVehicle();
                setState(CONNECTING);
            }
            public void onBindingDied(ComponentName n) {
                if (conn != this) return;
                binder = null;
                data = new SpdVehicle();
                setState(FAILED);
            }
            public void onNullBinding(ComponentName n) { onBindingDied(n); }
        };
        try {
            Intent i = new Intent().setComponent(new ComponentName(PKG, SERVICE));
            if (app.bindService(i, c, Context.BIND_AUTO_CREATE)) {
                conn = c;
                return;
            }
            try { app.unbindService(c); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        setState(FAILED);
    }

    /** Hilo del CAN. */
    private void connected(IBinder b) {
        try {
            if (!DESCRIPTOR.equals(b.getInterfaceDescriptor())) { setState(FAILED); return; }
            if (codeInt == 0 || codeBundle == 0) readCodes();
        } catch (Throwable e) {
            setState(FAILED);
            return;
        }
        model = 0;
        distUnit = energyUnit = null;
        tick = 0;
        speedPushAt = 0;
        data = new SpdVehicle();
        subscribed = null;   // si el servicio se reinició, la suscripción anterior se perdió con él
        binder = b;
        setState(CONNECTED);
        worker.removeCallbacks(poll);
        syncPush.run();
        if (running) worker.post(poll);
    }

    /** Los códigos salen de las clases del servicio, así nunca se manda otra cosa por error. */
    private void readCodes() throws Exception {
        Context pc = app.createPackageContext(PKG, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        Class<?> stub = Class.forName(DESCRIPTOR + "$Stub", false, pc.getClassLoader());
        codeInt = staticInt(stub, "TRANSACTION_getInt");
        codeBundle = staticInt(stub, "TRANSACTION_getBundle");
        try { codeFloat = staticInt(stub, "TRANSACTION_getFloat"); } catch (Exception ignored) { codeFloat = 0; }
        try {   // sin estos códigos no hay avisos, pero las lecturas siguen
            int reg = staticInt(stub, "TRANSACTION_registerCallback");
            int unreg = staticInt(stub, "TRANSACTION_unregisterCallback");
            int changed = staticInt(Class.forName(CALLBACK + "$Stub", false, pc.getClassLoader()),
                    "TRANSACTION_onCarInfoBundleChanged");
            codeRegister = reg;
            codeUnregister = unreg;
            codeChanged = changed;
            try {
                codeSpeedChanged = staticInt(Class.forName(CALLBACK + "$Stub", false, pc.getClassLoader()),
                        "TRANSACTION_onCarInfoFloatChanged");
            } catch (Throwable ignored) { codeSpeedChanged = 0; }
        } catch (Throwable ignored) { }
    }

    private static int staticInt(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            HandlerThread current = thread;
            if (!running || current == null || Looper.myLooper() != current.getLooper()) return;
            IBinder b = binder;
            try { read(b, tick++); } catch (Throwable ignored) { }
            Handler w = worker;
            if (running && current == thread && w != null) w.postDelayed(this, TICK_MS);
        }
    };

    // ── avisos al momento (puertas y velocidad) ──

    /** Hilo del CAN: pide los avisos mientras se lee y se da de baja al parar. */
    private final Runnable syncPush = new Runnable() {
        public void run() {
            IBinder b = binder;
            if (running && b != null && subscribed == null && codeRegister != 0 && codeChanged != 0) {
                if (push(b, codeRegister, true)) subscribed = b;
            } else if (!running && subscribed != null) {
                push(subscribed, codeUnregister, false);
                subscribed = null;
            }
        }
    };

    /** registerCallback(paquete, categorías, aviso) o unregisterCallback(paquete, categorías). */
    private boolean push(IBinder b, int code, boolean register) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(app.getPackageName());
            d.writeStringArray(PUSH);
            if (register) d.writeStrongBinder(callback);
            if (!b.transact(code, d, r, 0)) return false;
            r.readException();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            r.recycle();
            d.recycle();
        }
    }

    /**
     * Lo que el servicio llama cuando cambia un valor (en un hilo de Binder). Solo se usan puertas y velocidad:
     * se copia y se pasa al hilo del CAN. Los demás avisos se aceptan sin más.
     */
    private final Binder callback = new Binder() {
        { attachInterface(null, CALLBACK); }

        @Override
        protected boolean onTransact(int code, Parcel d, Parcel r, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, d, r, flags);
            try {
                d.enforceInterface(CALLBACK);
                if (code == codeChanged && d.readInt() == DOORS) {
                    final Bundle x = d.readInt() != 0 ? Bundle.CREATOR.createFromParcel(d) : null;
                    if (x != null) x.size();   // lo desempaqueta antes de soltar el mensaje
                    Handler w = worker;
                    if (w != null) w.post(new Runnable() { public void run() { doorsChanged(x); } });
                } else if (code == codeSpeedChanged && d.readInt() == SPEED) {
                    final float speed = d.readFloat();
                    final int arg = d.readInt();
                    final long at = SystemClock.elapsedRealtime();
                    Handler w = worker;
                    if (arg == 0 && w != null) w.post(new Runnable() { public void run() { speedChanged(speed, at); } });
                }
            } catch (Throwable ignored) { }
            if (r != null) r.writeNoException();
            return true;
        }
    };

    /** Hilo del CAN. Si el aviso no trae las dos puertas delanteras, se lee el estado completo. */
    private void doorsChanged(Bundle x) {
        IBinder b = binder;
        if (!running || b == null) return;
        SpdVehicle.Doors d = doors(x);
        if (d == null) d = doors(getBundle(b, DOORS));
        if (d == null) return;
        SpdVehicle v = data.copy();
        v.doors = d;
        publish(v);
    }

    /** Hilo del CAN. No reutiliza velocidades de demostración ni la hora de otra lectura del carro. */
    private void speedChanged(float speed, long at) {
        if (!running || binder == null) return;
        SpdVehicle v = data.copy();
        speedPushAt = at;
        setSpeed(v, speed, at);
        publish(v);
    }

    private void setSpeed(SpdVehicle v, Float speed, long at) {
        v.speedKmh = model == BMW_MINI && speed != null && !Float.isNaN(speed) && !Float.isInfinite(speed)
                && speed >= 0 && speed <= 400 ? speed : null;
        v.speedAt = v.speedKmh != null ? at : 0;
    }

    // ── lectura y significado de cada valor ──

    /** Vuelta n.º {@code t} (una por segundo); la 0 lo lee todo. */
    private void read(IBinder b, int t) {
        SpdVehicle v = data.copy();
        Float volts = readVoltage();
        boolean got = volts != null || v.batteryVolts != null;
        v.batteryVolts = volts;   // una lectura ausente o inválida borra la anterior
        if (b == null) {
            got |= v.speedKmh != null;
            v.speedKmh = null;
            v.speedAt = 0;
            speedPushAt = 0;
            if (got) publish(v);
            return;
        }
        if (t % SLOW_EVERY == 0) {
            v.services = null;
            v.rangeKm = v.odometerKm = v.fuelLitres = v.avgSpeedKmh = v.consumption = null;
            distUnit = energyUnit = null;
            Integer m = getInt(b, MODEL);
            if (m != null && m != 0) model = m;
            Bundle units = getBundle(b, UNITS);
            if (units != null && intOf(units, "mSetEnergyUnit") != null) energyUnit = intOf(units, "mSetEnergyUnit");
            List<SpdVehicle.Service> sv = services(getBundle(b, SERVICES));
            if (sv != null) { v.services = sv; got = true; }

            Float range = distance(getBundle(b, RANGE), 3000);
            if (range != null) { v.rangeKm = range; got = true; }
            Float odo = distance(getBundle(b, ODOMETER), 2000000);
            if (odo != null) { v.odometerKm = odo; got = true; }

            // litros que quedan en el tanque (40 L): coincide con las rayas del tablero
            Integer fuel = getInt(b, FUEL);
            if (model == BMW_MINI && fuel != null && fuel >= 1 && fuel <= 150) { v.fuelLitres = (float) fuel; got = true; }

            // ordenador de a bordo: autonomía, consumo y velocidad media (sin unidad propia: la de RANGE y UNITS)
            Bundle trip = getBundle(b, TRIP);
            if (trip != null && distUnit != null && distUnit == 0) {
                Float r = floatOf(trip, "mCarArriveMile"), sp = floatOf(trip, "mCarAverageSpeed"), en = floatOf(trip, "mCarEnergy");
                if (r != null && r > 0 && r <= 3000) { v.rangeKm = r; got = true; }
                if (sp != null && sp > 0 && sp <= 300) { v.avgSpeedKmh = sp; got = true; }
                if (en != null && en > 0 && en <= 50 && energyUnit != null && energyUnit == 0) { v.consumption = en; got = true; }
            }
        }

        long speedReadAt = SystemClock.elapsedRealtime();
        Float speed = getFloat(b, SPEED);
        // Los avisos recientes tienen prioridad: algunos firmwares solo actualizan el callback, no el getter.
        if (speedPushAt == 0 || speedReadAt - speedPushAt > SpdVehicle.SPEED_MAX_AGE_MS)
            setSpeed(v, speed, speedReadAt);
        got = true; // publica también la pérdida de velocidad para activar el GPS de reserva

        SpdVehicle.Doors doors = doors(getBundle(b, DOORS));   // por si no llega el aviso
        if (doors != null) { v.doors = doors; got = true; }

        Bundle out = getBundle(b, OUTSIDE);
        v.outsideC = null;
        Integer outUnit = out != null ? intOf(out, "unit") : null;
        if (outUnit != null && outUnit == 0) {   // 0 = °C
            Float c = floatOf(out, "value");
            if (c != null && c >= -60 && c <= 70) { v.outsideC = c; got = true; }
        }

        if (t % TYRES_EVERY == 0) {
            Float[] tyres = tyres(getBundle(b, TYRES));
            v.tyresBar = tyres;
            got = true;
        }
        if (t % SLOW_EVERY == 0) {
            long at = SystemClock.elapsedRealtime();
            v.spdDiagnostics = readDiagnostics(b, speed);
            v.spdDiagnosticsAt = at;
            got = true;
        }
        if (got) publish(v);
    }

    /** Solo IDs identificados en las capturas; no explora categorías ni interpreta ceros como estados. */
    private Map<String, String> readDiagnostics(IBinder b, Float speed) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("spd_check", rawBundle(getBundle(b, 120291), "mCheckControlTotal", "mCheckControlProject"));
        values.put("spd_acc", raw(getInt(b, 140086)));
        values.put("spd_brake", raw(getInt(b, 140030)));
        values.put("spd_handbrake", raw(getInt(b, 120080)));
        values.put("spd_parking", raw(getInt(b, 140010)));
        values.put("spd_gear", raw(getInt(b, 140080)));
        values.put("spd_rpm_100004", raw(getInt(b, 100004)));
        values.put("spd_rpm_100042", raw(getInt(b, 100042)));
        values.put("spd_rpm_120340", raw(getInt(b, 120340)));
        values.put("spd_speed", raw(speed));
        values.put("spd_lights", raw(getInt(b, 100174)));
        values.put("spd_brightness", rawBundle(getBundle(b, 120280), "mLightCBrightness"));
        Bundle oil = getBundle(b, 120289);
        String oilRaw = rawBundle(oil, "mEngineOilSupport", "mEngineOilValid", "mEngineOilState", "mEngineOilValue");
        if (oilRaw != null && (Integer.valueOf(0).equals(intOf(oil, "mEngineOilSupport"))
                || Integer.valueOf(0).equals(intOf(oil, "mEngineOilValid")))) oilRaw = "Lectura no disponible\n" + oilRaw;
        values.put("spd_oil", oilRaw);
        return Collections.unmodifiableMap(values);
    }

    private static String rawBundle(Bundle bundle, String... keys) {
        if (bundle == null) return null;
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (!bundle.containsKey(key)) continue;
            if (out.length() > 0) out.append('\n');
            String value = raw(bundle.get(key));
            out.append(key).append(" = ").append(value != null ? value : "—");
        }
        return out.length() > 0 ? out.toString() : null;
    }

    /** Limita la salida del fabricante para no llenar el diagnóstico con arrays o textos anómalos. */
    private static String raw(Object value) {
        if (value == null) return null;
        if (value instanceof Float && (Float.isNaN((Float) value) || Float.isInfinite((Float) value))) return null;
        if (value instanceof Double && (Double.isNaN((Double) value) || Double.isInfinite((Double) value))) return null;
        if (value instanceof int[]) {
            int[] array = (int[]) value;
            return Arrays.toString(Arrays.copyOf(array, Math.min(array.length, 32))) + (array.length > 32 ? "…" : "");
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < Math.min(list.size(), 32); i++) {
                Object item = list.get(i);
                if (!(item instanceof Number)) return null;
                String itemText = raw(item);
                if (itemText == null) return null;
                if (i > 0) text.append(", ");
                text.append(itemText);
            }
            return text.append(']').append(list.size() > 32 ? "…" : "").toString();
        }
        if (!(value instanceof Number) && !(value instanceof Boolean) && !(value instanceof String)) return null;
        String text = String.valueOf(value);
        return text.length() <= 256 ? text : text.substring(0, 256) + "…";
    }

    private Float readVoltage() {
        try { return voltage(Settings.System.getInt(app.getContentResolver(), CURRENT_VOLTAGE, -1)); }
        catch (RuntimeException e) { return null; }
    }

    /** Décimas de voltio: 123 = 12,3 V; escala confirmada por el dueño de la central el 13-nov-2026. */
    private static Float voltage(int raw) { return raw > 0 && raw <= 320 ? raw / 10f : null; }

    private void publish(SpdVehicle v) {
        HandlerThread current = thread;
        if (!running || current == null || Looper.myLooper() != current.getLooper()) return;
        v.updatedAt = System.currentTimeMillis();
        data = v;
        notifyUi();
    }

    /** {frontLeft, frontRight, rearLeft, rearRight, trunk, hood}, 1 = abierta. Sin las dos delanteras no vale. */
    private static SpdVehicle.Doors doors(Bundle d) {
        if (d == null) return null;
        Integer fl = intOf(d, "frontLeft"), fr = intOf(d, "frontRight");
        if (fl == null || fr == null) return null;
        return new SpdVehicle.Doors(fl != 0, fr != 0, nz(intOf(d, "rearLeft")) != 0, nz(intOf(d, "rearRight")) != 0,
                nz(intOf(d, "trunk")) != 0, nz(intOf(d, "hood")) != 0);
    }

    /** {unit, value}: unit 0 = km (también fija la unidad del ordenador de a bordo). */
    private Float distance(Bundle d, float max) {
        if (d == null) return null;
        Integer unit = intOf(d, "unit");
        if (unit == null) return null;
        distUnit = unit;
        Float km = floatOf(d, "value");
        return unit == 0 && km != null && km >= 1 && km <= max ? km : null;
    }

    /** Décimas de bar; -1 = rueda sin lectura (la radio la muestra como «-0.1»). */
    private static Float[] tyres(Bundle t) {
        if (t == null || nz(intOf(t, "mTyrePressValid")) != 1 || nz(intOf(t, "mTyrePressDataValid")) != 1) return null;
        if (nz(intOf(t, "mTyrePressUnit")) != 1) return null;   // 1 = bar
        int[] raw = intsOf(t, "mTyrePressData");
        if (raw == null || raw.length == 0) return null;
        Float[] bar = new Float[raw.length];
        for (int i = 0; i < raw.length; i++) bar[i] = raw[i] >= 0 && raw[i] <= 60 ? raw[i] / 10f : null;
        return bar;
    }

    /** Listas paralelas, una posición por mantenimiento. Año en dos cifras; distancia «restante» en km. */
    private static List<SpdVehicle.Service> services(Bundle s) {
        if (s == null) return null;
        int[] code = intsOf(s, "mMaintainProjectNumber");
        if (code == null) return null;
        int n = Math.max(0, Math.min(code.length, intOf(s, "mMaintainTotal") != null ? intOf(s, "mMaintainTotal") : code.length));
        int[] year = intsOf(s, "mMaintainDateYear"), month = intsOf(s, "mMaintainDateMonth"), dateOk = intsOf(s, "mMaintainDateValid");
        int[] dist = intsOf(s, "mMaintainDistance"), distOk = intsOf(s, "mMaintainDistanceValid");
        int[] distUnitArr = intsOf(s, "mMaintainDistanceUnit"), distType = intsOf(s, "mMaintainDistanceType");
        int[] st = intsOf(s, "mMaintainProjectState");
        List<SpdVehicle.Service> out = new ArrayList<SpdVehicle.Service>();
        for (int i = 0; i < n; i++) {
            boolean dated = at(dateOk, i) == 1;
            Integer y = dated && at(year, i) >= 0 && at(year, i) <= 99 ? 2000 + at(year, i) : null;
            Integer mo = dated && at(month, i) >= 1 && at(month, i) <= 12 ? at(month, i) : null;
            Integer left = at(distOk, i) == 1 && at(distUnitArr, i) == 1 && at(distType, i) == 0 && at(dist, i) >= 0 ? at(dist, i) : null;
            out.add(new SpdVehicle.Service(code[i], y, mo, left, at(st, i) == 0));
        }
        return out;
    }

    // ── llamadas al servicio (lecturas AIDL a mano) ──

    private Integer getInt(IBinder b, int id) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(id);
            d.writeInt(0);
            if (!b.transact(codeInt, d, r, 0)) return null;
            r.readException();
            return r.readInt();
        } catch (Throwable e) {
            return null;
        } finally {
            r.recycle();
            d.recycle();
        }
    }

    private Float getFloat(IBinder b, int id) {
        if (codeFloat == 0) return null;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(id);
            d.writeInt(0);
            if (!b.transact(codeFloat, d, r, 0)) return null;
            r.readException();
            float value = r.readFloat();
            return Float.isNaN(value) || Float.isInfinite(value) ? null : value;
        } catch (Throwable e) {
            return null;
        } finally {
            r.recycle();
            d.recycle();
        }
    }

    private Bundle getBundle(IBinder b, int id) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(id);
            d.writeInt(0);
            if (!b.transact(codeBundle, d, r, 0)) return null;
            r.readException();
            if (r.readInt() == 0) return null;
            Bundle x = Bundle.CREATOR.createFromParcel(r);
            x.size();   // lo desempaqueta antes de soltar la respuesta
            return x;
        } catch (Throwable e) {
            return null;
        } finally {
            r.recycle();
            d.recycle();
        }
    }

    private static Integer intOf(Bundle b, String k) {
        Object o = b.get(k);
        return o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte ? ((Number) o).intValue() : null;
    }

    private static Float floatOf(Bundle b, String k) {
        Object o = b.get(k);
        if (!(o instanceof Number)) return null;
        float f = ((Number) o).floatValue();
        return Float.isNaN(f) || Float.isInfinite(f) ? null : f;
    }

    private static int[] intsOf(Bundle b, String k) {
        Object o = b.get(k);
        if (o instanceof int[]) return (int[]) o;
        if (o instanceof short[]) { short[] a = (short[]) o; int[] r = new int[a.length]; for (int i = 0; i < a.length; i++) r[i] = a[i]; return r; }
        if (o instanceof byte[]) { byte[] a = (byte[]) o; int[] r = new int[a.length]; for (int i = 0; i < a.length; i++) r[i] = a[i]; return r; }
        if (o instanceof List) {
            List<?> a = (List<?>) o;
            int[] r = new int[a.size()];
            for (int i = 0; i < r.length; i++) {
                if (!(a.get(i) instanceof Number)) return null;
                r[i] = ((Number) a.get(i)).intValue();
            }
            return r;
        }
        return null;
    }

    private static int at(int[] a, int i) { return a != null && i < a.length ? a[i] : Integer.MIN_VALUE; }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    private void setState(int s) {
        state = s;
        notifyUi();
    }

    private final Runnable notifyTask = new Runnable() {
        public void run() {
            Listener l = listener;
            if (l != null) l.onCarInfo();
        }
    };

    private void notifyUi() {
        main.removeCallbacks(notifyTask);
        main.post(notifyTask);
    }
}
