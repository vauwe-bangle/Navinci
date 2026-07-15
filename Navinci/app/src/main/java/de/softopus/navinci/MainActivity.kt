// Navinci
// Copyright (c) 2026 vauwe-digital / softopus
// Licensed under GNU General Public License v3.0

package de.softopus.navinci

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    internal lateinit var webView:    WebView
    internal lateinit var bleManager: BleManager
    internal lateinit var gpsManager: GpsManager
    internal var webViewReady = false

    private var syncHandler:  Handler?  = null
    private var syncRunnable: Runnable? = null

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        const val REQUEST_IMPORT_CSV          = 101
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        private const val OSMAND_PKG_PLUS = "net.osmand.plus"
        private const val OSMAND_PKG_FREE = "net.osmand"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WebView programmatisch erstellen — kein XML-Layout nötig
        webView = WebView(this)
        val layout = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(layout)
        webView.addJavascriptInterface(Bridge(this), "NativeBridge")

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccessFromFileURLs      = true
            allowUniversalAccessFromFileURLs = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.clearCache(true)
        webView.clearHistory()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webViewReady = true
                if (hasPermissions()) { gpsManager.start(); startForegroundTracking() }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")

        bleManager = BleManager(
            activity      = this,
            onSpeed       = { kmh ->
                val json = "{\"speed\":${"%.1f".format(java.util.Locale.US, kmh)},\"source\":\"ble\"}"
                sendToJS("updateCscSpeed", json)
            },
            onCadence     = { rpm -> sendToJS("updateCadence", "{\"cadence\":$rpm}") },
            onSpeedStatus = { status ->
                if (status == "disconnected" || status == "timeout")
                    sendToJS("updateCscSpeed", "{\"speed\":null,\"source\":\"gps\"}")
                sendToJS("updateSpeedStatus", "\"$status\"")
            },
            onCadStatus   = { status -> sendToJS("updateCadStatus", "\"$status\"") }
        )

        gpsManager  = GpsManager(this)  { json -> sendToJS("updateGps",  json) }
        checkAndRequestPermissions()
    }

    // ── OsmAnd ────────────────────────────────────────────────────────────

    internal fun launchOsmAnd() {
        for (pkg in listOf(OSMAND_PKG_PLUS, OSMAND_PKG_FREE)) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT  // öffnet im anderen Split-Screen-Feld
            )
            // Hinweis wenn Split-Screen noch nicht aktiv
            if (!isInMultiWindowMode) {
                Toast.makeText(
                    this,
                    "Tipp: Navinci zuerst in Split-Screen setzen → dann OsmAnd hier tippen",
                    Toast.LENGTH_LONG
                ).show()
            }
            startActivity(intent)
            return
        }
        Toast.makeText(this, "OsmAnd ist nicht installiert", Toast.LENGTH_SHORT).show()
    }

    // ── Foreground Service ────────────────────────────────────────────────

    private fun startForegroundTracking() {
        if (!TrackingService.isRunning)
            startForegroundService(Intent(this, TrackingService::class.java))
    }

    private fun startServiceSync() {
        stopServiceSync()
        syncHandler = Handler(Looper.getMainLooper())
        syncRunnable = object : Runnable {
            override fun run() {
                if (webViewReady && TrackingService.isRunning) {
                    val json = "{\"speed\":${"%.1f".format(java.util.Locale.US, TrackingService.currentSpeed)}," +
                            "\"distance\":${"%.2f".format(java.util.Locale.US, TrackingService.currentDistance)}," +
                            "\"seconds\":${TrackingService.currentSeconds}," +
                            "\"altitude\":${"%.0f".format(java.util.Locale.US, TrackingService.currentAltitude)}," +
                            "\"gain\":${"%.0f".format(java.util.Locale.US, TrackingService.currentGain)}," +
                            "\"baroAvailable\":${TrackingService.baroAvailable}}"
                    sendToJS("updateFromService", json)
                }
                syncHandler?.postDelayed(this, 1000)
            }
        }
        syncHandler?.postDelayed(syncRunnable!!, 1000)
    }

    private fun stopServiceSync() {
        syncRunnable?.let { syncHandler?.removeCallbacks(it) }
        syncHandler = null; syncRunnable = null
    }

    // ── CSV-Import ────────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_CSV && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val csv = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
                val esc = csv.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "")
                sendToJS("importCsvData", "\"$esc\"")
            } catch (e: Exception) {
                sendToJS("showToast", "\"Import-Fehler: ${e.message}\"")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    internal fun sendToJS(fn: String, json: String) {
        runOnUiThread { if (webViewReady) webView.evaluateJavascript("window.$fn($json)", null) }
    }

    internal fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("InlinedApi")
    internal fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (webViewReady) { gpsManager.start(); startForegroundTracking() }
            } else sendToJS("onPermissionDenied", "{}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions() && webViewReady) { gpsManager.start(); startForegroundTracking() }
        startServiceSync()
    }

    override fun onPause()   { super.onPause();   gpsManager.stop(); stopServiceSync() }
    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnectCsc(); gpsManager.stop(); stopServiceSync()
        stopService(Intent(this, TrackingService::class.java)); webView.destroy()
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// JavaScript Bridge — aufgerufen als window.NativeBridge.*
// ═════════════════════════════════════════════════════════════════════════════

@Suppress("unused")
class Bridge(private val ctx: MainActivity) {

    /** Tempo-Sensor suchen und verbinden (Slot 1) */
    @JavascriptInterface
    fun startSpeedScan() {
        if (ctx.hasPermissions()) ctx.bleManager.scanForSpeed() else ctx.checkAndRequestPermissions()
    }

    /** Kadenz-Sensor suchen und verbinden (Slot 2) */
    @JavascriptInterface
    fun startCadenceScan() {
        if (ctx.hasPermissions()) ctx.bleManager.scanForCadence() else ctx.checkAndRequestPermissions()
    }

    /** Tempo-Sensor trennen */
    @JavascriptInterface fun stopSpeedSensor()  { ctx.bleManager.disconnectSpeed() }

    /** Kadenz-Sensor trennen */
    @JavascriptInterface fun stopCadSensor()    { ctx.bleManager.disconnectCadence() }

    /** Beide Sensoren trennen */
    @JavascriptInterface fun stopCsc()          { ctx.bleManager.disconnectCsc() }

    /** OsmAnd öffnen */
    @JavascriptInterface fun launchOsmAnd() { ctx.runOnUiThread { ctx.launchOsmAnd() } }

    /** Alle installierten Apps mit Launcher-Icon als JSON-Array zurückgeben */
    @JavascriptInterface
    fun getInstalledApps(): String {
        val pm      = ctx.packageManager
        val intent  = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            android.content.pm.PackageManager.MATCH_ALL else 0

        val activities = pm.queryIntentActivities(intent, flags)
        val result = activities
            .map { info ->
                val name = info.loadLabel(pm).toString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                val pkg  = info.activityInfo.packageName
                "{\"name\":\"$name\",\"pkg\":\"$pkg\"}"
            }
            .distinctBy { it }
            .sortedBy { it.lowercase() }
        return "[${result.joinToString(",")}]"
    }
    @JavascriptInterface
    fun launchApp(packageName: String) {
        ctx.runOnUiThread {
            val intent = ctx.packageManager.getLaunchIntentForPackage(packageName.trim())
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                )
                if (!ctx.isInMultiWindowMode) {
                    Toast.makeText(ctx,
                        "Tipp: Navinci zuerst in Split-Screen setzen",
                        Toast.LENGTH_LONG).show()
                }
                ctx.startActivity(intent)
            } else {
                Toast.makeText(ctx,
                    "App nicht gefunden: $packageName",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** GPS starten/stoppen */
    @JavascriptInterface fun startGps() { if (ctx.hasPermissions()) ctx.gpsManager.start() }
    @JavascriptInterface fun stopGps()  { ctx.gpsManager.stop() }

    /**
     * Radumfang für Geschwindigkeitsberechnung setzen.
     * Wird von JS beim App-Start und beim Speichern der Einstellungen aufgerufen.
     * @param meters  Radumfang in Metern (z.B. 2.105 für 700c × 25mm)
     */
    @JavascriptInterface
    fun setWheelCircumference(meters: Float) { ctx.bleManager.wheelCircumference = meters }

    /** Fahrtdaten zurücksetzen */
    @JavascriptInterface
    fun resetService() {
        TrackingService.resetRequested  = true
        TrackingService.currentSeconds  = 0
        TrackingService.currentDistance = 0.0
        TrackingService.currentSpeed    = 0f
    }

    /** Display-Wakelock (verhindert Displayabschaltung beim Fahren) */
    @JavascriptInterface
    fun setWakeLock(enable: Boolean) {
        ctx.runOnUiThread {
            if (enable) ctx.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else        ctx.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** CSV-Import via System-Filepicker */
    @JavascriptInterface
    fun importCsv() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        ctx.startActivityForResult(Intent.createChooser(i, "CSV-Datei auswählen"), MainActivity.REQUEST_IMPORT_CSV)
    }

    /**
     * CSV-Export: JS legt Export-Daten in localStorage (navinci_csv_export / navinci_csv_label),
     * Kotlin liest sie aus und speichert ins Downloads-Verzeichnis.
     */
    @JavascriptInterface
    fun exportCsv(signal: String) {
        Handler(Looper.getMainLooper()).post {
            ctx.webView.evaluateJavascript("localStorage.getItem('navinci_csv_label')") { labelRaw ->
                val label = labelRaw?.removeSurrounding("\"") ?: "export"
                ctx.webView.evaluateJavascript("localStorage.getItem('navinci_csv_export')") { csv ->
                    if (csv == null || csv == "null") return@evaluateJavascript
                    val clean = csv.removeSurrounding("\"")
                        .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                    try {
                        val name = "navinci_${label}_${System.currentTimeMillis()}.csv"
                        val dir  = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        java.io.File(dir, name).writeText(clean, Charsets.UTF_8)
                        ctx.webView.evaluateJavascript("localStorage.removeItem('navinci_csv_export')", null)
                        ctx.webView.evaluateJavascript("localStorage.removeItem('navinci_csv_label')", null)
                        ctx.webView.evaluateJavascript("window.showToast('✓ Gespeichert: $name')", null)
                    } catch (e: Exception) {
                        ctx.webView.evaluateJavascript("window.showToast('Fehler: ${e.message}')", null)
                    }
                }
            }
        }
    }

    @JavascriptInterface fun getAppVersion(): String = BuildConfig.VERSION_NAME
}