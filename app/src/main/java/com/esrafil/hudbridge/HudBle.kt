package com.esrafil.hudbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/** HUD-ESP32'ye BLE ile satır satır komut gönderir. Kopunca kendisi yeniden bağlanır. */
@SuppressLint("MissingPermission")
object HudBle {
    private val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private const val DEVICE_NAME = "HUD-ESP32"

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var wantConnect = false
    private var sending = false
    private val queue = ArrayDeque<String>()
    private val desired = LinkedHashMap<String, String>()
    private val lastSent = HashMap<String, String>()

    @Volatile var connected = false
        private set
    @Volatile var status = "Bağlı değil"
        private set
    var statusListener: (() -> Unit)? = null

    private fun setStatus(s: String) {
        status = s
        AppLog.add("BLE: $s")
        main.post { statusListener?.invoke() }
    }

    private fun hasPerms(c: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun connect(context: Context) {
        ctx = context.applicationContext
        wantConnect = true
        main.post { startScan() }
    }

    fun disconnect() {
        wantConnect = false
        main.post {
            stopScan()
            gatt?.close()
            gatt = null
            rx = null
            connected = false
            queue.clear()
            setStatus("Bağlı değil")
        }
    }

    private fun startScan() {
        val c = ctx ?: return
        if (!wantConnect || connected || scanning) return
        if (!hasPerms(c)) { setStatus("Bluetooth izni yok"); return }
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth kapalı")
            main.postDelayed({ startScan() }, 5000)
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCb)
            scanning = true
            setStatus("HUD aranıyor...")
        } catch (e: Exception) {
            setStatus("Arama başlamadı: ${e.message}")
            main.postDelayed({ startScan() }, 5000)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            val c = ctx ?: return
            val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                stopScan()
                val c = ctx ?: return@post
                setStatus("HUD bulundu, bağlanıyor...")
                gatt = result.device.connectGatt(c, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                setStatus("Arama hatası $errorCode")
                main.postDelayed({ startScan() }, 5000)
            }
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post {
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                    rx = null
                    connected = false
                    sending = false
                    queue.clear()
                    setStatus("Bağlantı koptu")
                    if (wantConnect) main.postDelayed({ startScan() }, 2000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID)
            if (ch == null) {
                main.post { setStatus("HUD servisi bulunamadı") }
                return
            }
            try { g.requestMtu(64) } catch (_: Exception) {}
            main.post {
                rx = ch
                connected = true
                lastSent.clear()
                for (line in desired.values) queue.addLast(line)
                setStatus("Bağlı")
                pump()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (sending) return
        val g = gatt
        val c = rx
        if (!connected || g == null || c == null) return
        val line = queue.removeFirstOrNull() ?: return
        sending = true
        val bytes = (line + "\n").toByteArray()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = bytes
                g.writeCharacteristic(c)
            }
        } catch (_: Exception) {
        }
        main.postDelayed({ sending = false; pump() }, 40)
    }

    /** Durum komutu: aynı değer tekrar gönderilmez, bağlanınca son değerler yeniden gönderilir. */
    fun setState(key: String, line: String) {
        main.post {
            desired[key] = line
            if (connected && lastSent[key] != line) {
                lastSent[key] = line
                queue.addLast(line)
                pump()
            }
        }
    }

    /** Tek seferlik komut (test düğmeleri). */
    fun sendNow(line: String) {
        main.post {
            if (!connected) { AppLog.add("Gönderilemedi (bağlı değil): $line"); return@post }
            queue.addLast(line)
            pump()
            AppLog.add("Gönderildi: $line")
        }
    }

    /** Navigasyon bitti: ekranı temizle. */
    fun clearAll() {
        main.post {
            desired.clear()
            lastSent.clear()
            if (connected) {
                queue.addLast("TEMIZLE")
                pump()
            }
        }
    }
}
