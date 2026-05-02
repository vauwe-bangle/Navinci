// Navinci
// Copyright (c) 2026 vauwe-digital / softopus
// Licensed under GNU General Public License v3.0

package de.softopus.navinci

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class BaroManager(
    private val activity: Activity,
    private val onData: (String) -> Unit   // JSON: {"altitude":…, "gain":…, "available":true}
) {
    private val TAG = "BaroManager"

    private val sensorManager =
        activity.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    // Referenzdruck für relative Höhenberechnung (erster Messwert)
    private var refPressure: Float = -1f

    // Elevation-Gain-Tracking
    private var lastAltitude:    Float = Float.NaN
    private var totalGainM:      Float = 0f          // akkumulierte Aufstiegsmeter
    private val GAIN_THRESHOLD = 2.0f               // Mindestanstieg in m (Rauschen filtern)

    // Einfacher gleitender Mittelwert (5 Werte) gegen Druckrauschen
    private val window = ArrayDeque<Float>(5)
    private val WINDOW_SIZE = 5

    val isAvailable: Boolean get() = pressureSensor != null

    // ── Start ──────────────────────────────────────────────────────────────

    fun start() {
        if (pressureSensor == null) {
            Log.w(TAG, "Kein Barometersensor verfügbar – GPS-Höhe als Fallback")
            onData("""{"altitude":null,"gain":0,"available":false}""")
            return
        }
        sensorManager.registerListener(sensorListener, pressureSensor,
            SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "Barometer gestartet")
    }

    // ── Stop ───────────────────────────────────────────────────────────────

    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        Log.d(TAG, "Barometer gestoppt")
    }

    // ── Reset (bei neuem Start einer Fahrt) ───────────────────────────────

    fun reset() {
        refPressure  = -1f
        lastAltitude = Float.NaN
        totalGainM   = 0f
        window.clear()
        Log.d(TAG, "Barometer zurückgesetzt")
    }

    // ── Sensor-Listener ───────────────────────────────────────────────────

    private val sensorListener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            val pressure = event.values[0]   // hPa

            // Gleitender Mittelwert
            if (window.size >= WINDOW_SIZE) window.removeFirst()
            window.addLast(pressure)
            val avgPressure = window.average().toFloat()

            // Ersten stabilen Wert als Referenz setzen (nach 5 Messungen)
            if (refPressure < 0f) {
                if (window.size < WINDOW_SIZE) return
                refPressure = avgPressure
                Log.d(TAG, "Referenzdruck gesetzt: $refPressure hPa")
            }

            // Relative Höhe über Barometerhöhenformel (ICAO-Standardatmosphäre)
            // SensorManager.getAltitude(p0, p) = 44330 × (1 − (p/p0)^(1/5.255))
            val altitude = SensorManager.getAltitude(refPressure, avgPressure)

            // Elevation Gain berechnen
            if (!lastAltitude.isNaN()) {
                val delta = altitude - lastAltitude
                if (delta >= GAIN_THRESHOLD) {
                    totalGainM  += delta
                    lastAltitude = altitude
                } else if (delta < -GAIN_THRESHOLD) {
                    // Abstieg: nur lastAltitude aktualisieren, kein Gain
                    lastAltitude = altitude
                }
                // Kleinschwankungen innerhalb ±GAIN_THRESHOLD → ignorieren
            } else {
                lastAltitude = altitude
            }

            val json = """{"altitude":${"%.0f".format(altitude)},"gain":${"%.0f".format(totalGainM)},"available":true}"""
            onData(json)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
}