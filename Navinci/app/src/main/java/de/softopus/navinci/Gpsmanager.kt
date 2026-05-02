// Navinci
// Copyright (c) 2026 vauwe-digital / softopus
// Licensed under GNU General Public License v3.0

package de.softopus.navinci

import android.app.Activity
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GpsManager(
    private val activity: Activity,
    private val onData: (String) -> Unit   // JSON: {"speed":…, "distance":…}
) {
    private val TAG = "GpsManager"

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var totalKm = 0.0

    private val locationManager =
        activity.getSystemService(android.content.Context.LOCATION_SERVICE)
                as LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val speedKmh = loc.speed * 3.6f
            val display  = if (speedKmh < 2f) 0f else speedKmh

            if (lastLat != 0.0 && display > 0f) {
                totalKm += haversine(lastLat, lastLon, loc.latitude, loc.longitude)
            }
            lastLat = loc.latitude
            lastLon = loc.longitude

            val json = """{"speed":${"%.1f".format(java.util.Locale.US, display)},"distance":${"%.2f".format(java.util.Locale.US, totalKm)},"altitude":${"%.0f".format(java.util.Locale.US, loc.altitude)}}"""
            Log.d(TAG, "GPS: $json")
            onData(json)
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String)  { Log.d(TAG, "GPS aktiviert: $provider") }
        override fun onProviderDisabled(provider: String) { Log.w(TAG, "GPS deaktiviert: $provider") }
    }

    fun start() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, 0f, locationListener
            )
            Log.d(TAG, "GPS gestartet (nativer LocationManager)")
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS-Berechtigung fehlt: ${e.message}")
        }
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
        Log.d(TAG, "GPS gestoppt")
    }

    fun resetDistance() {
        totalKm = 0.0
        lastLat = 0.0
        lastLon = 0.0
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}