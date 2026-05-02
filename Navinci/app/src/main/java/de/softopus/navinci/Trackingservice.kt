// Navinci
// Copyright (c) 2026 vauwe-digital / softopus
// Licensed under GNU General Public License v3.0

package de.softopus.navinci

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class TrackingService : Service() {

    private val TAG        = "TrackingService"
    private val CHANNEL_ID = "navinci_tracking"
    private val NOTIF_ID   = 1

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager:   SensorManager

    private var totalKm     = 0.0
    private var lastLat     = 0.0
    private var lastLon     = 0.0
    private var rideSeconds = 0
    private var timerThread: Thread? = null

    // Barometer
    private var refPressure  = -1f
    private var lastAltitude = Float.NaN
    private val baroWindow   = ArrayDeque<Float>(5)
    private val BARO_WINDOW  = 5
    private val GAIN_THRESH  = 2.0f

    companion object {
        var currentSpeed      = 0f
        var currentDistance   = 0.0
        var currentSeconds    = 0
        var currentAltitude   = 0f
        var currentGain       = 0f
        var baroAvailable     = false
        var isRunning         = false
        var resetRequested    = false
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager   = getSystemService(Context.SENSOR_SERVICE)   as SensorManager
        startForeground(NOTIF_ID, buildNotification())
        startGps(); startBaro(); startTimer()
        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGps(); stopBaro()
        timerThread?.interrupt()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // GPS
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (resetRequested) {
                totalKm = 0.0; lastLat = 0.0; lastLon = 0.0
                rideSeconds = 0; currentSeconds = 0
                currentDistance = 0.0; currentSpeed = 0f
                refPressure = -1f; lastAltitude = Float.NaN
                currentAltitude = 0f; currentGain = 0f
                baroWindow.clear()
                resetRequested = false
                return
            }
            val speedKmh = loc.speed * 3.6f
            currentSpeed = if (speedKmh < 2f) 0f else speedKmh
            if (lastLat != 0.0 && currentSpeed > 0f) {
                totalKm += haversine(lastLat, lastLon, loc.latitude, loc.longitude)
                currentDistance = totalKm
            }
            lastLat = loc.latitude; lastLon = loc.longitude
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String)  {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startGps() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        } catch (e: SecurityException) { Log.e(TAG, "GPS fehlt: ${e.message}") }
    }
    private fun stopGps() { locationManager.removeUpdates(locationListener) }

    // Barometer
    private val baroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val pressure = event.values[0]
            if (baroWindow.size >= BARO_WINDOW) baroWindow.removeFirst()
            baroWindow.addLast(pressure)
            val avg = baroWindow.average().toFloat()
            if (refPressure < 0f) {
                if (baroWindow.size < BARO_WINDOW) return
                refPressure = avg
            }
            val altitude = SensorManager.getAltitude(refPressure, avg)
            currentAltitude = altitude
            if (!lastAltitude.isNaN()) {
                val delta = altitude - lastAltitude
                if (delta >= GAIN_THRESH) { currentGain += delta; lastAltitude = altitude }
                else if (delta < -GAIN_THRESH) lastAltitude = altitude
            } else lastAltitude = altitude
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun startBaro() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        baroAvailable = sensor != null
        if (sensor != null) {
            sensorManager.registerListener(baroListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Barometer gestartet")
        } else Log.w(TAG, "Kein Barometersensor")
    }
    private fun stopBaro() { sensorManager.unregisterListener(baroListener) }

    // Timer
    private fun startTimer() {
        timerThread = Thread {
            while (!Thread.interrupted()) {
                if (resetRequested) { rideSeconds = 0; currentSeconds = 0 }
                else if (currentSpeed > 0f) { rideSeconds++; currentSeconds = rideSeconds }
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            }
        }
        timerThread?.start()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Navinci Tracking", NotificationManager.IMPORTANCE_LOW))
        val speed = "%.1f".format(java.util.Locale.US, currentSpeed)
        val km    = "%.2f".format(java.util.Locale.US, currentDistance)
        val gain  = "%.0f".format(java.util.Locale.US, currentGain)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Navinci")
            .setContentText("$speed km/h · $km km · ↑$gain m")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).build()
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1-a))
    }
}