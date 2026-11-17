package com.irvingguerra.litedash.feature.obd;

import java.util.List;
import java.util.Map;

/** Lo que el carro informa por el CAN (lo lee SpdCarInfo). null = el carro todavía no lo ha enviado. */
final class SpdVehicle {
    static final long SPEED_MAX_AGE_MS = 3000;

    /** Puertas: true = abierta. */
    static final class Doors {
        final boolean frontLeft, frontRight, rearLeft, rearRight, trunk, hood;

        Doors(boolean frontLeft, boolean frontRight, boolean rearLeft, boolean rearRight, boolean trunk, boolean hood) {
            this.frontLeft = frontLeft;
            this.frontRight = frontRight;
            this.rearLeft = rearLeft;
            this.rearRight = rearRight;
            this.trunk = trunk;
            this.hood = hood;
        }

        boolean anyOpen() { return frontLeft || frontRight || rearLeft || rearRight || trunk || hood; }
    }

    /** Un mantenimiento que lleva el propio carro (aceite, inspección, líquido de frenos). */
    static final class Service {
        final int code;
        final Integer year, month, remainingKm;   // null = el carro no lo indica
        final boolean ok;

        Service(int code, Integer year, Integer month, Integer remainingKm, boolean ok) {
            this.code = code;
            this.year = year;
            this.month = month;
            this.remainingKm = remainingKm;
            this.ok = ok;
        }

        /** En español (se traduce al mostrarlo). */
        String label() {
            switch (code) {
                case 1: return "Aceite del motor";
                case 2: return "Inspección del vehículo";
                case 3: return "Líquido de frenos";
                default: return "Servicio " + code;
            }
        }
    }

    Float fuelLitres, rangeKm, consumption, avgSpeedKmh, outsideC, odometerKm;   // L, km, L/100 km, km/h, °C, km
    Float batteryVolts;          // tensión que informa la central; null = lectura no disponible
    Float speedKmh;              // CURRENT_SPEED de SPD; km/h en el perfil BMW MINI
    long speedAt;                // elapsedRealtime de la lectura, independiente de las demás propiedades
    Float[] tyresBar;            // presión de cada rueda en el orden del carro; null = esa rueda sin lectura
    List<Service> services;
    Doors doors;
    Map<String, String> spdDiagnostics; // valores brutos, sin convertir unidades ni interpretar códigos; mapa inmutable
    long spdDiagnosticsAt;             // elapsedRealtime de la última consulta, independiente del reloj de la central
    long updatedAt;              // 0 = nunca

    Float currentSpeedKmh(long now) {
        return speedKmh != null && !Float.isNaN(speedKmh) && !Float.isInfinite(speedKmh)
                && speedKmh >= 0 && speedKmh <= 400 && speedAt > 0
                && now >= speedAt && now - speedAt <= SPEED_MAX_AGE_MS ? speedKmh : null;
    }

    SpdVehicle copy() {
        SpdVehicle v = new SpdVehicle();
        v.fuelLitres = fuelLitres;
        v.rangeKm = rangeKm;
        v.consumption = consumption;
        v.avgSpeedKmh = avgSpeedKmh;
        v.outsideC = outsideC;
        v.odometerKm = odometerKm;
        v.batteryVolts = batteryVolts;
        v.speedKmh = speedKmh;
        v.speedAt = speedAt;
        v.tyresBar = tyresBar;
        v.services = services;
        v.doors = doors;
        v.spdDiagnostics = spdDiagnostics;
        v.spdDiagnosticsAt = spdDiagnosticsAt;
        v.updatedAt = updatedAt;
        return v;
    }
}
