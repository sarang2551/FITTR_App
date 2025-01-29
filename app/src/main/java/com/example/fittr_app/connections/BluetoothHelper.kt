package com.example.fittr_app.connections

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.fittr_app.BluetoothReadCallback

@SuppressLint("MissingPermission")
class BluetoothHelper(
    private val context: Context,
    private val readCallback: BluetoothReadCallback? = null
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var serviceUUID = "4c72c9a7-69af-4a0b-8630-fab8f513fb9e" // Default service UUID
    private var characteristicUUID = "" // To be specified dynamically in the read function
    private var readMode = false
    private var sendMode = false
    private var messageToSend: String? = null

    private val connectionTimeoutHandler = Handler(Looper.getMainLooper())
    private val connectionTimeoutRunnable = Runnable {
        if (bluetoothGatt == null) {
            Log.e("BluetoothHelper", "Connection timed out")
            readCallback?.onError("Connection timed out")
        }
    }
    public fun serviceIDCheck(id:String):Boolean{
        return id == serviceUUID
    }

    private fun startConnectionTimeout() {
        connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable, 10000) // 10-second timeout
    }

    private fun clearConnectionTimeout() {
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable)
    }

    // Reading characteristics dynamically
    fun connectAndRead(device: BluetoothDevice, characteristicUUID: String): Boolean {
        this.characteristicUUID = characteristicUUID
        readMode = true
        sendMode = false

        return handleConnection(device)
    }

    // Function to write a characteristic value
    fun connectAndSendMessage(device: BluetoothDevice, message: String, characteristicUUID: String): Boolean {
        this.messageToSend = message
        this.characteristicUUID = characteristicUUID
        readMode = false
        sendMode = true
        Log.d("BluetoothHelper", "Connecting and sending message: $message")
        return handleConnection(device)
    }

    // Handles connection logic
    private fun handleConnection(device: BluetoothDevice): Boolean {
        // Check if the device is already connected
        if (bluetoothGatt != null) {
            Log.d("BluetoothHelper", "Device already connected. Proceeding with operation.")
            bluetoothGatt?.discoverServices()
            return true
        }
        bluetoothGatt = device.connectGatt(context, true, gattCallback)
        Log.d("BluetoothHelper", "Connecting to device UUID: ${device.name}")
        startConnectionTimeout()
        if (bluetoothGatt == null) {
            Log.e("BluetoothHelper", "Failed to connect to ${device.name}")
            readCallback?.onError("Failed to connect to ${device.name}")
            clearConnectionTimeout()
            return false
        }
        return true

    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d("BluetoothHelper", "Device onConnectionStateChange: status: $status, newState: $newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d("BluetoothHelper", "Successfully connected to GATT server")
                gatt.discoverServices()
            } else{
                Log.e("BluetoothHelper", "Disconnected from GATT server")
                bluetoothGatt = null
                readCallback?.onError("Disconnected from GATT server")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BluetoothHelper", "GATT services discovered")
                if (readMode) {
                    readCharacteristic(gatt)
                } else if (sendMode) {
                    sendMessage(gatt)
                }
            } else {
                Log.e("BluetoothHelper", "Service discovery failed with status $status")
                readCallback?.onError("Service discovery failed with status $status")
            }
        }

        private fun readCharacteristic(gatt: BluetoothGatt) {
            val service = gatt.getService(java.util.UUID.fromString(serviceUUID))
            if (service != null) {
                Log.d("BluetoothHelper", "Reading from ${service.uuid}")
                val characteristic = service.getCharacteristic(java.util.UUID.fromString(characteristicUUID))
                if (characteristic != null && isCharacteristicReadable(characteristic)) {
                    val success = gatt.readCharacteristic(characteristic)
                    if (success) {
                        Log.d("BluetoothHelper", "Read request sent successfully")
                    } else {
                        Log.e("BluetoothHelper", "Failed to send read request")
                        readCallback?.onError("Failed to send read request")
                    }
                } else {
                    Log.e("BluetoothHelper", "Characteristic not found or not readable")
                    readCallback?.onError("Characteristic not found or not readable")
                }
            } else {
                Log.e("BluetoothHelper", "Service not found")
                readCallback?.onError("Service not found")
            }
        }

        private fun sendMessage(gatt: BluetoothGatt) {
            val service = gatt.getService(java.util.UUID.fromString(serviceUUID))
            if (service != null) {
                Log.d("BluetoothHelper", "Sending message to ${service.uuid}")
                val characteristic = service.getCharacteristic(java.util.UUID.fromString(characteristicUUID))
                if(characteristic == null){
                    Log.e("BluetoothHelper", "Characteristic $characteristicUUID not found")
                    readCallback?.onError("Characteristic not found")
                    return
                }
                if (isCharacteristicWritable(characteristic)) {
                    characteristic.value = messageToSend?.toByteArray(Charsets.UTF_8)
                    val success = gatt.writeCharacteristic(characteristic)
                    if (success) {
                        Log.d("BluetoothHelper", "Message sent successfully: $messageToSend")
                    } else {
                        Log.e("BluetoothHelper", "Failed to send message")
                        readCallback?.onError("Failed to send message")
                    }
                } else {
                    Log.e("BluetoothHelper", "Characteristic not writable")
                    readCallback?.onError("Characteristic not found or not writable")
                }
            } else {
                Log.e("BluetoothHelper", "Service not found")
                readCallback?.onError("Service not found")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value.toString(Charsets.UTF_8)
                Log.d("BluetoothHelper", "Characteristic read successfully: $value")
                readCallback?.onValueRead(value)
            } else {
                Log.e("BluetoothHelper", "Failed to read characteristic with status $status")
                readCallback?.onError("Failed to read characteristic with status $status")
            }
        }

        private fun isCharacteristicWritable(characteristic: BluetoothGattCharacteristic): Boolean {
            return (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) > 0 ||
                    (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0
        }

        private fun isCharacteristicReadable(characteristic: BluetoothGattCharacteristic): Boolean {
            return (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) > 0
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt = null
    }
}
