package com.ff6locke.btremoteex

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

class BleHidServer(
    private val context: Context,
    private val status: (String) -> Unit
) {
    companion object {
        private val HID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        private val HID_INFO = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
        private val REPORT_MAP = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
        private val CONTROL_POINT = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
        private val REPORT = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        private val PROTOCOL_MODE = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
        private val PNP_ID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
        private val BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val DEVICE_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val REPORT_REF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")

        private val reportMap = byteArrayOf(
            0x05,0x01,0x09,0x02,0xA1.toByte(),0x01,0x09,0x01,0xA1.toByte(),0x00,0x85.toByte(),0x01,
            0x05,0x09,0x19,0x01,0x29,0x05,0x15,0x00,0x25,0x01,0x95.toByte(),0x05,0x75,0x01,0x81.toByte(),0x02,
            0x95.toByte(),0x01,0x75,0x03,0x81.toByte(),0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81.toByte(),0x25,0x7f,0x75,0x08,0x95.toByte(),0x03,0x81.toByte(),0x06,0xc0.toByte(),0xc0.toByte(),
            0x05,0x01,0x09,0x06,0xa1.toByte(),0x01,0x85.toByte(),0x02,0x05,0x07,0x19,0xe0.toByte(),0x29,0xe7.toByte(),0x15,0x00,0x25,0x01,0x75,0x01,0x95.toByte(),0x08,0x81.toByte(),0x02,
            0x95.toByte(),0x01,0x75,0x08,0x81.toByte(),0x01,0x19,0x00,0x29,0x65,0x15,0x00,0x25,0x65,0x75,0x08,0x95.toByte(),0x06,0x81.toByte(),0x00,0xc0.toByte(),
            0x05,0x0c,0x09,0x01,0xa1.toByte(),0x01,0x85.toByte(),0x03,0x15,0x00,0x26,0xff.toByte(),0x03,0x19,0x00,0x2a,0xff.toByte(),0x03,0x75,0x10,0x95.toByte(),0x01,0x81.toByte(),0x00,0xc0.toByte()
        )
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var mouseReport: BluetoothGattCharacteristic? = null
    private var keyboardReport: BluetoothGattCharacteristic? = null
    private var consumerReport: BluetoothGattCharacteristic? = null
    private val notifyEnabled = mutableSetOf<String>()
    private var connectedDevice: BluetoothDevice? = null
    @Volatile var isConnected: Boolean = false
        private set
    private var serviceReady = false
    private var batteryService: BluetoothGattService? = null
    private var deviceInfoService: BluetoothGattService? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private fun characteristic(uuid: UUID, properties: Int, permissions: Int, value: ByteArray? = null) =
        BluetoothGattCharacteristic(uuid, properties, permissions).apply { if (value != null) this.value = value }

    private fun addReport(service: BluetoothGattService, id: Int, report: BluetoothGattCharacteristic) {
        report.addDescriptor(BluetoothGattDescriptor(REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ).apply {
            value = byteArrayOf(id.toByte(), 0x01)
        })
        report.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(report)
    }

    fun start(): Boolean {
        val a = adapter ?: run { status("BLE unavailable on this phone"); return false }
        if (!a.isEnabled) { status("Turn on Bluetooth first"); return false }
        if (!a.isMultipleAdvertisementSupported || a.bluetoothLeAdvertiser == null) {
            status("This phone does not support BLE peripheral advertising")
            return false
        }
        stop()
        try {
            server = manager.openGattServer(context, callback)
            val s = BluetoothGattService(HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            s.addCharacteristic(characteristic(HID_INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, byteArrayOf(0x11,0x01,0x00,0x02)))
            s.addCharacteristic(characteristic(REPORT_MAP, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, reportMap))
            s.addCharacteristic(characteristic(CONTROL_POINT, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED, byteArrayOf(0)))
            s.addCharacteristic(characteristic(PROTOCOL_MODE, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED, byteArrayOf(1)))
            mouseReport = characteristic(REPORT, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, byteArrayOf(0,0,0,0))
            keyboardReport = characteristic(REPORT, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, ByteArray(8))
            consumerReport = characteristic(REPORT, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, byteArrayOf(0,0))
            addReport(s, 1, mouseReport!!)
            addReport(s, 2, keyboardReport!!)
            addReport(s, 3, consumerReport!!)
            if (server?.addService(s) != true) {
                status("Could not add BLE HID service")
                stop()
                return false
            }
            batteryService = BluetoothGattService(BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(characteristic(BATTERY_LEVEL, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ, byteArrayOf(100)))
            }
            deviceInfoService = BluetoothGattService(DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(characteristic(PNP_ID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ, byteArrayOf(0x02,0x6b,0x1d,0x01,0x00,0x01,0x00)))
            }
            advertiser = a.bluetoothLeAdvertiser
            serviceReady = false
            status("Starting BLE HID service…")
            return true
        } catch (e: SecurityException) {
            status("BLE permission denied")
            stop()
            return false
        } catch (e: Exception) {
            status("BLE start failed: ${e.message ?: "unknown error"}")
            stop()
            return false
        }
    }

    private fun beginAdvertising() {
        val adv = advertiser ?: return
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(HID))
                .setIncludeDeviceName(true)
                .build()
            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    status("BLE HID advertising as this phone — scan for Bluetooth keyboard")
                }
                override fun onStartFailure(errorCode: Int) {
                    status("BLE advertising failed (code $errorCode)")
                }
            }
            advertiseCallback?.let { adv.startAdvertising(settings, data, it) }
        } catch (e: SecurityException) {
            status("BLE advertising permission denied")
        } catch (e: Exception) {
            status("BLE advertising error: ${e.message ?: "unknown"}")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(statusCode: Int, service: BluetoothGattService) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("BLE HID service setup failed ($statusCode)")
                return
            }
            when (service.uuid) {
                HID -> batteryService?.let { server?.addService(it) }
                BATTERY -> deviceInfoService?.let { server?.addService(it) }
                DEVICE_INFO -> {
                    serviceReady = true
                    beginAdvertising()
                }
            }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, statusCode: Int, newState: Int) {
            isConnected = statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            if (isConnected) {
                connectedDevice = device
                status("BLE HID connected to ${device.name ?: "host"}")
            } else {
                notifyEnabled.remove(device.address)
                connectedDevice = null
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status("BLE GATT connection failed: status=$statusCode, state=$newState")
                } else {
                    status("BLE HID disconnected (state=$newState)")
                }
            }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (descriptor.uuid == CCCD) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) notifyEnabled.add(device.address)
                else notifyEnabled.remove(device.address)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: byteArrayOf()
            val result = if (offset <= value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            server?.sendResponse(device, requestId, if (offset <= value.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset, result)
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (characteristic.uuid == PROTOCOL_MODE && value.isNotEmpty()) characteristic.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    fun sendMouse(report: ByteArray) = notify(mouseReport, report)
    fun sendKeyboard(report: ByteArray) = notify(keyboardReport, report)
    fun sendConsumer(report: ByteArray) = notify(consumerReport, report)

    private fun notify(characteristic: BluetoothGattCharacteristic?, value: ByteArray) {
        val s = server ?: return
        val c = characteristic ?: return
        if (!isConnected || notifyEnabled.isEmpty()) return
        c.value = value
        val device = connectedDevice?.takeIf { it.address in notifyEnabled } ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(device, c, false, value)
            else {
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, c, false)
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        try { advertiseCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) {}
        try { server?.clearServices(); server?.close() } catch (_: Exception) {}
        server = null
        advertiser = null
        mouseReport = null
        keyboardReport = null
        consumerReport = null
        notifyEnabled.clear()
        isConnected = false
        connectedDevice = null
        serviceReady = false
        batteryService = null
        deviceInfoService = null
        advertiseCallback = null
    }
}
