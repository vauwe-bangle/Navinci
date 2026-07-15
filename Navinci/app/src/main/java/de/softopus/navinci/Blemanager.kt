// Navinci
// Copyright (c) 2026 vauwe-digital / softopus
// Licensed under GNU General Public License v3.0

package de.softopus.navinci

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BleManager — zwei unabhängige CSC-Sensor-Slots
 *
 * scanForSpeed()   → Slot 1 (Radsensor / Wheel Revolution Data)
 * scanForCadence() → Slot 2 (Kurbelsensor / Crank Revolution Data)
 *
 * Ein kombinierter Sensor (Bit0+Bit1) über scanForSpeed() verbunden
 * liefert automatisch auch Kadenz.
 *
 * Fix 99 km/h: MIN_DTIME filtert Burst-Pakete beim Connect,
 * MAX_SPEED verwirft unplausible Werte.
 */
class BleManager(
    private val activity:      Activity,
    private val onSpeed:       ((Float)  -> Unit)? = null,
    private val onCadence:     ((Int)    -> Unit)? = null,
    private val onSpeedStatus: ((String) -> Unit)? = null,
    private val onCadStatus:   ((String) -> Unit)? = null
) {
    private val TAG = "BleManager"

    private val bluetoothAdapter =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gattSpeed:   BluetoothGatt? = null
    private var gattCadence: BluetoothGatt? = null

    private var scanningSpeed   = false
    private var scanningCadence = false

    private val handler          = Handler(Looper.getMainLooper())
    private var cadenceResetJob: Runnable? = null

    var wheelCircumference = 2.105f   // Meter

    // CSC GATT-UUIDs (Bluetooth SIG Standard)
    private val CSC_SERVICE = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    private val CSC_CHAR    = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
    private val CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // CSC-Zustand
    private var lastWheelRevs = -1L;  private var lastWheelTime = -1
    private var lastCrankRevs = -1;   private var lastCrankTime = -1

    // Fix 99 km/h
    private val MIN_DTIME = 50       // 1/1024 s ≈ 49 ms — filtert Burst-Pakete
    private val MAX_SPEED = 120f     // km/h — Plausibilitätsgrenze

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    // ── Scan Tempo-Sensor ─────────────────────────────────────────────────

    fun scanForSpeed() {
        if (scanningSpeed) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(speedScanCallback)
            scanningSpeed = false
        }
        Log.d(TAG, "Scan Tempo-Sensor…")
        handler.post { onSpeedStatus?.invoke("scanning") }
        bluetoothAdapter.bluetoothLeScanner.startScan(null, scanSettings, speedScanCallback)
        scanningSpeed = true
        handler.postDelayed({
            if (scanningSpeed) {
                bluetoothAdapter.bluetoothLeScanner.stopScan(speedScanCallback)
                scanningSpeed = false
                Log.d(TAG, "Tempo-Sensor: Timeout")
                handler.post { onSpeedStatus?.invoke("timeout") }
            }
        }, 30_000)
    }

    private val speedScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            if (!uuids.any { it.uuid == CSC_SERVICE }) return
            Log.d(TAG, "Tempo-Sensor gefunden: ${result.device.name} (${result.device.address})")
            bluetoothAdapter.bluetoothLeScanner.stopScan(this)
            scanningSpeed = false
            gattSpeed = result.device.connectGatt(
                activity, false, makeGattCallback(isSpeed = true), BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Tempo-Scan fehlgeschlagen: $errorCode")
            scanningSpeed = false
            handler.post { onSpeedStatus?.invoke("timeout") }
        }
    }

    // ── Scan Kadenz-Sensor ────────────────────────────────────────────────

    fun scanForCadence() {
        if (scanningCadence) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(cadenceScanCallback)
            scanningCadence = false
        }
        Log.d(TAG, "Scan Kadenz-Sensor…")
        handler.post { onCadStatus?.invoke("scanning") }
        bluetoothAdapter.bluetoothLeScanner.startScan(null, scanSettings, cadenceScanCallback)
        scanningCadence = true
        handler.postDelayed({
            if (scanningCadence) {
                bluetoothAdapter.bluetoothLeScanner.stopScan(cadenceScanCallback)
                scanningCadence = false
                Log.d(TAG, "Kadenz-Sensor: Timeout")
                handler.post { onCadStatus?.invoke("timeout") }
            }
        }, 30_000)
    }

    private val cadenceScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            if (!uuids.any { it.uuid == CSC_SERVICE }) return
            Log.d(TAG, "Kadenz-Sensor gefunden: ${result.device.name} (${result.device.address})")
            bluetoothAdapter.bluetoothLeScanner.stopScan(this)
            scanningCadence = false
            gattCadence = result.device.connectGatt(
                activity, false, makeGattCallback(isSpeed = false), BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Kadenz-Scan fehlgeschlagen: $errorCode")
            scanningCadence = false
            handler.post { onCadStatus?.invoke("timeout") }
        }
    }

    // ── GATT Callback Factory ─────────────────────────────────────────────

    private fun makeGattCallback(isSpeed: Boolean) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "${if (isSpeed) "Tempo" else "Kadenz"}-Sensor verbunden")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "${if (isSpeed) "Tempo" else "Kadenz"}-Sensor getrennt")
                    g.close()
                    if (isSpeed) {
                        gattSpeed = null; resetWheelState()
                        handler.post { onSpeedStatus?.invoke("disconnected") }
                    } else {
                        gattCadence = null; resetCrankState()
                        handler.post { onCadStatus?.invoke("disconnected") }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val ch = g.getService(CSC_SERVICE)?.getCharacteristic(CSC_CHAR) ?: run {
                Log.e(TAG, "CSC Characteristic nicht gefunden"); return
            }
            g.setCharacteristicNotification(ch, true)
            handler.postDelayed({
                val desc = ch.getDescriptor(CCCD) ?: return@postDelayed
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
                Log.d(TAG, "${if (isSpeed) "Tempo" else "Kadenz"}-Sensor: Notifications aktiviert")
            }, 600)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) { if (ch.uuid == CSC_CHAR) parseCsc(value, isSpeed) }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) { if (ch.uuid == CSC_CHAR) parseCsc(ch.value, isSpeed) }
    }

    // ── CSC Parser ────────────────────────────────────────────────────────

    private fun parseCsc(value: ByteArray, isSpeedSlot: Boolean) {
        if (value.isEmpty()) return
        val flags     = value[0].toInt() and 0xFF
        val wheelPres = (flags and 0x01) != 0
        val crankPres = (flags and 0x02) != 0
        var off = 1

        // ── Wheel Revolution Data ─────────────────────────────────────────
        if (wheelPres && value.size >= off + 6) {
            val cumRev =
                (value[off  ].toLong() and 0xFF) or
                        ((value[off+1].toLong() and 0xFF) shl 8) or
                        ((value[off+2].toLong() and 0xFF) shl 16) or
                        ((value[off+3].toLong() and 0xFF) shl 24)
            val evtTime = (value[off+4].toInt() and 0xFF) or
                    ((value[off+5].toInt() and 0xFF) shl 8)

            if (lastWheelRevs >= 0 && lastWheelTime >= 0) {
                val dRev  = cumRev - lastWheelRevs
                var dTime = evtTime - lastWheelTime
                if (dTime < 0) dTime += 65536

                if (dRev > 0 && dTime >= MIN_DTIME) {
                    val speedKmh = (wheelCircumference * dRev) / (dTime / 1024f) * 3.6f
                    if (speedKmh <= MAX_SPEED) {
                        Log.d(TAG, "Speed: ${"%.1f".format(speedKmh)} km/h")
                        handler.post {
                            onSpeedStatus?.invoke("connected")
                            onSpeed?.invoke(speedKmh)
                        }
                    } else {
                        Log.w(TAG, "Speed unplausibel: ${"%.1f".format(speedKmh)} km/h → ignoriert")
                    }
                } else if (dRev == 0L && dTime >= MIN_DTIME) {
                    handler.post { onSpeed?.invoke(0f) }
                }
            }
            lastWheelRevs = cumRev; lastWheelTime = evtTime
            off += 6
        }

        // ── Crank Revolution Data ─────────────────────────────────────────
        if (crankPres && value.size >= off + 4) {
            val cumRev  = (value[off  ].toInt() and 0xFF) or ((value[off+1].toInt() and 0xFF) shl 8)
            val evtTime = (value[off+2].toInt() and 0xFF) or ((value[off+3].toInt() and 0xFF) shl 8)

            if (lastCrankRevs >= 0 && lastCrankTime >= 0) {
                val dRev  = (cumRev - lastCrankRevs + 65536) % 65536
                var dTime = evtTime - lastCrankTime
                if (dTime < 0) dTime += 65536

                if (dRev > 0 && dTime >= MIN_DTIME) {
                    val rpm = (dRev * 1024 * 60) / dTime
                    Log.d(TAG, "Cadence: $rpm rpm")
                    handler.post {
                        onCadStatus?.invoke("connected")
                        onCadence?.invoke(rpm)
                    }
                } else if (dRev == 0 && dTime >= MIN_DTIME) {
                    handler.post { onCadence?.invoke(0) }
                }
            }
            lastCrankRevs = cumRev; lastCrankTime = evtTime

            cadenceResetJob?.let { handler.removeCallbacks(it) }
            cadenceResetJob = Runnable { handler.post { onCadence?.invoke(0) } }
            handler.postDelayed(cadenceResetJob!!, 3000)
        }
    }

    // ── Trennen ───────────────────────────────────────────────────────────

    fun disconnectSpeed() {
        gattSpeed?.disconnect(); gattSpeed?.close(); gattSpeed = null
        resetWheelState()
        Log.d(TAG, "Tempo-Sensor getrennt")
    }

    fun disconnectCadence() {
        cadenceResetJob?.let { handler.removeCallbacks(it) }
        gattCadence?.disconnect(); gattCadence?.close(); gattCadence = null
        resetCrankState()
        Log.d(TAG, "Kadenz-Sensor getrennt")
    }

    fun disconnectCsc() { disconnectSpeed(); disconnectCadence() }

    private fun resetWheelState() { lastWheelRevs = -1L; lastWheelTime = -1 }
    private fun resetCrankState() { lastCrankRevs = -1;  lastCrankTime = -1 }
}