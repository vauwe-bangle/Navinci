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

class BleManager(
    private val activity: Activity,
    private val onSpeed:     ((Float) -> Unit)? = null,   // km/h vom Radsensor
    private val onCadence:   ((Int)   -> Unit)? = null,   // RPM vom Kurbelsensor
    private val onCscStatus: ((String) -> Unit)? = null   // "connected" | "disconnected" | "timeout"
) {
    private val TAG = "BleManager"

    private val bluetoothManager =
        activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gattCsc: BluetoothGatt? = null

    private val handler           = Handler(Looper.getMainLooper())
    private var cadenceResetJob: Runnable? = null

    // Radumfang in Metern (Standard: 700c × 25mm ≈ 2.105 m)
    // Wird von MainActivity per setWheelCircumference() aktualisiert
    var wheelCircumference = 2.105f

    // ── CSC GATT-UUIDs (Bluetooth SIG Standard) ───────────────────────────
    private val CSC_SERVICE = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    private val CSC_CHAR    = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
    private val CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // CSC-Zustandsgrößen für differentielle Berechnung
    private var lastWheelRevs = -1L
    private var lastWheelTime = -1        // 1/1024 s
    private var lastCrankRevs = -1
    private var lastCrankTime = -1        // 1/1024 s

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // ── Scan ──────────────────────────────────────────────────────────────

    fun scanCsc() {
        Log.d(TAG, "Starte BLE-Scan (CSC)…")
        bluetoothAdapter.bluetoothLeScanner.startScan(null, scanSettings, scanCscCallback)
        handler.postDelayed({
            bluetoothAdapter.bluetoothLeScanner.stopScan(scanCscCallback)
            if (gattCsc == null) {
                Log.d(TAG, "CSC Scan Timeout")
                onCscStatus?.invoke("timeout")
            }
        }, 30_000)
    }

    private val scanCscCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            if (uuids.any { it.uuid == CSC_SERVICE }) {
                Log.d(TAG, "CSC Sensor gefunden: ${result.device.name} (${result.device.address})")
                bluetoothAdapter.bluetoothLeScanner.stopScan(this)
                connectCsc(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "CSC Scan fehlgeschlagen: $errorCode")
            onCscStatus?.invoke("timeout")
        }
    }

    // ── Verbinden ─────────────────────────────────────────────────────────

    private fun connectCsc(device: BluetoothDevice) {
        Log.d(TAG, "Verbinde CSC: ${device.name}")
        gattCsc = device.connectGatt(activity, false, gattCscCallback)
    }

    private val gattCscCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "CSC verbunden")
                    g.discoverServices()
                    handler.post { onCscStatus?.invoke("connected") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "CSC getrennt (status=$status)")
                    gattCsc = null
                    resetCscState()
                    cadenceResetJob?.let { handler.removeCallbacks(it) }
                    handler.post { onCscStatus?.invoke("disconnected") }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val ch = g.getService(CSC_SERVICE)?.getCharacteristic(CSC_CHAR) ?: run {
                Log.e(TAG, "CSC Characteristic nicht gefunden")
                return
            }
            g.setCharacteristicNotification(ch, true)
            handler.postDelayed({
                val desc = ch.getDescriptor(CCCD) ?: return@postDelayed
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
                Log.d(TAG, "CSC Notifications aktiviert")
            }, 500)
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) { parseCscPacket(value) }

        // API < 33
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) { parseCscPacket(ch.value) }
    }

    // ── CSC Measurement Parser (Bluetooth SIG 0x2A5B) ────────────────────
    //
    // Byte 0: Flags
    //   Bit 0 = Wheel Revolution Data present
    //   Bit 1 = Crank Revolution Data present
    //
    // Wheel (wenn Bit 0):
    //   Byte 1–4  Cumulative Wheel Revolutions  (uint32, LE)
    //   Byte 5–6  Last Wheel Event Time          (uint16, 1/1024 s, LE)
    //
    // Crank (wenn Bit 1, nach Wheel-Block):
    //   Byte x+0–1  Cumulative Crank Revolutions (uint16, LE)
    //   Byte x+2–3  Last Crank Event Time         (uint16, 1/1024 s, LE)

    private fun parseCscPacket(value: ByteArray) {
        if (value.isEmpty()) return
        val flags      = value[0].toInt() and 0xFF
        val wheelPres  = (flags and 0x01) != 0
        val crankPres  = (flags and 0x02) != 0
        var offset     = 1

        // ── Radgeschwindigkeit ────────────────────────────────────────────
        if (wheelPres && value.size >= offset + 6) {
            val cumWheelRevs =
                (value[offset  ].toLong() and 0xFF) or
                        ((value[offset+1].toLong() and 0xFF) shl 8) or
                        ((value[offset+2].toLong() and 0xFF) shl 16) or
                        ((value[offset+3].toLong() and 0xFF) shl 24)
            val wheelTime =
                (value[offset+4].toInt() and 0xFF) or
                        ((value[offset+5].toInt() and 0xFF) shl 8)

            if (lastWheelRevs >= 0 && lastWheelTime >= 0) {
                val dRev  = cumWheelRevs - lastWheelRevs
                var dTime = wheelTime - lastWheelTime
                if (dTime < 0) dTime += 65536          // 16-Bit-Überlauf

                if (dRev > 0 && dTime > 0) {
                    // v [m/s] = (Umfang × ΔRev) / (ΔTime / 1024)  →  [km/h]
                    val speedKmh = (wheelCircumference * dRev) / (dTime / 1024f) * 3.6f
                    Log.d(TAG, "Speed: ${"%.1f".format(speedKmh)} km/h")
                    onSpeed?.invoke(speedKmh)
                } else if (dRev == 0L && dTime > 0) {
                    onSpeed?.invoke(0f)   // Stillstand
                }
            }
            lastWheelRevs = cumWheelRevs
            lastWheelTime = wheelTime
            offset += 6
        }

        // ── Trittfrequenz ─────────────────────────────────────────────────
        if (crankPres && value.size >= offset + 4) {
            val cumCrankRevs =
                (value[offset  ].toInt() and 0xFF) or
                        ((value[offset+1].toInt() and 0xFF) shl 8)
            val crankTime =
                (value[offset+2].toInt() and 0xFF) or
                        ((value[offset+3].toInt() and 0xFF) shl 8)

            if (lastCrankRevs >= 0 && lastCrankTime >= 0) {
                val dRev  = (cumCrankRevs - lastCrankRevs + 65536) % 65536
                var dTime = crankTime - lastCrankTime
                if (dTime < 0) dTime += 65536

                if (dRev > 0 && dTime > 0) {
                    val rpm = (dRev * 1024 * 60) / dTime
                    Log.d(TAG, "Cadence: $rpm rpm")
                    onCadence?.invoke(rpm)
                } else if (dRev == 0) {
                    onCadence?.invoke(0)
                }
            }
            lastCrankRevs = cumCrankRevs
            lastCrankTime = crankTime

            // Stillstand-Fallback: kein neues Paket nach 3 s → 0 rpm
            cadenceResetJob?.let { handler.removeCallbacks(it) }
            cadenceResetJob = Runnable {
                Log.d(TAG, "Cadence Stillstand → 0 rpm")
                onCadence?.invoke(0)
            }
            handler.postDelayed(cadenceResetJob!!, 3000)
        }
    }

    // ── Trennen ───────────────────────────────────────────────────────────

    fun disconnectCsc() {
        cadenceResetJob?.let { handler.removeCallbacks(it) }
        gattCsc?.disconnect()
        gattCsc?.close()
        gattCsc = null
        resetCscState()
        Log.d(TAG, "CSC getrennt")
    }

    private fun resetCscState() {
        lastWheelRevs = -1L
        lastWheelTime = -1
        lastCrankRevs = -1
        lastCrankTime = -1
    }
}