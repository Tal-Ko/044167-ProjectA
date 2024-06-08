package com.example.hrvapplication;
// Based on:
/*
 * (c) Matey Nenov (https://www.thinker-talk.com)
 *
 * Licensed under Creative Commons: By Attribution 3.0
 * http://creativecommons.org/licenses/by/3.0/
 *
 */

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static android.bluetooth.BluetoothProfile.GATT;

import androidx.annotation.RequiresPermission;

public class BLEController {
    private static BLEController instance;

    private BluetoothLeScanner scanner;
    private BluetoothDevice device;
    private BluetoothGatt bluetoothGatt;
    private BluetoothManager bluetoothManager;

    private BluetoothGattCharacteristic cmdGattChar = null;         // BLEWrite
    private BluetoothGattCharacteristic responseGattChar = null;    // BLERead | BLENotify
    private BluetoothGattCharacteristic bpmGattChar = null;         // BLERead | BLENotify
    private BluetoothGattCharacteristic liveSignalGattChar = null;  // BLERead | BLENotify
    private BluetoothGattCharacteristic liveRRGattChar = null;      // BLERead | BLENotify

    private ArrayList<BLEControllerListener> listeners = new ArrayList<>();
    private HashMap<String, BluetoothDevice> devices = new HashMap<>();

    final String hrvServiceUUID = "0777dfa9-204b-11ef-8fea-646ee0fcbb46";
    final String hrvCommandCharacteristicUUID = "07dba383-204b-11ef-a096-646ee0fcbb46";

    final String hrvResponseCharacteristicUUID = "5f0b1b60-2177-11ef-971d-646ee0fcbb46";
    final String hrvBPMCharacteristicUUID = "45ed7702-21d5-11ef-8771-646ee0fcbb46";
    final String hrvLiveSignalCharacteristicUUID = "f0a7ba94-2426-11ef-bb71-646ee0fcbb46";
    final String hrvLiveRRCharacteristicUUID = "f187ef45-2426-11ef-bb71-646ee0fcbb46";

    List<BluetoothGattCharacteristic> notifyChars = new ArrayList<>();

    private BLEController(Context ctx) {
        this.bluetoothManager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    public static BLEController getInstance(Context ctx) {
        if (null == instance)
            instance = new BLEController((ctx));

        return instance;
    }

    public void addBLEControllerListener(BLEControllerListener l) {
        if (!this.listeners.contains(l))
            this.listeners.add(l);
    }

    public void removeBLEControllerListener(BLEControllerListener l) {
        this.listeners.remove(l);
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_SCAN")
    public void init() {
        this.devices.clear();
        this.scanner = this.bluetoothManager.getAdapter().getBluetoothLeScanner();
        scanner.startScan(bleCallback);
    }

    private ScanCallback bleCallback = new ScanCallback() {
        @Override
        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            if (!devices.containsKey(device.getAddress()) && isThisTheDevice(device)) {
                deviceFound(device);
            }
        }

        @Override
        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult sr : results) {
                BluetoothDevice device = sr.getDevice();
                if (device == null) continue;
                if (!devices.containsKey(device.getAddress()) && isThisTheDevice(device)) {
                    deviceFound(device);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.i("[BLE]", "scan failed with errorcode: " + errorCode);
        }
    };

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private boolean isThisTheDevice(BluetoothDevice device) {
        return null != device && null != device.getName() && device.getName().equals("Nano 33 BLE Rev2 HRV");
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private void deviceFound(BluetoothDevice device) {
        if (device == null) return;
        this.devices.put(device.getAddress(), device);
        fireDeviceFound(device);
    }

    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"})
    public void connectToDevice(String address) {
        this.device = this.devices.get(address);
        if (this.device == null) return;

        this.scanner.stopScan(this.bleCallback);
        Log.i("[BLE]", "connect to device " + device.getAddress());
        this.bluetoothGatt = device.connectGatt(null, false, this.bleConnectCallback);
    }

    private final BluetoothGattCallback bleConnectCallback = new BluetoothGattCallback() {
        @Override
        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("[BLE]", "start service discovery " + bluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cmdGattChar = null;
                responseGattChar = null;
                bpmGattChar = null;
                liveSignalGattChar = null;
                liveRRGattChar = null;
                Log.w("[BLE]", "DISCONNECTED with status " + status);
                fireDisconnected();
            } else {
                Log.i("[BLE]", "unknown state " + newState + " and status " + status);
            }
        }

        private boolean hasAllCharacteristics() {
            return cmdGattChar != null
                    && responseGattChar != null
                    && bpmGattChar != null
                    && liveSignalGattChar != null
                    && liveRRGattChar != null;
        }

        @Override
        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            if (!hasAllCharacteristics()) {
                for (BluetoothGattService service : gatt.getServices()) {
                    if (service.getUuid().toString().equalsIgnoreCase(hrvServiceUUID)) {
                        List<BluetoothGattCharacteristic> gattCharacteristics = service.getCharacteristics();
                        for (BluetoothGattCharacteristic bgc : gattCharacteristics) {
                            int chprop = bgc.getProperties();
                            if (bgc.getUuid().toString().equalsIgnoreCase(hrvCommandCharacteristicUUID)) {
                                if (((chprop & BluetoothGattCharacteristic.PROPERTY_WRITE) | (chprop & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) > 0) {
                                    cmdGattChar = bgc;
                                }
                            } else if (bgc.getUuid().toString().equalsIgnoreCase(hrvResponseCharacteristicUUID)) {
                                if (((chprop & BluetoothGattCharacteristic.PROPERTY_READ) | (chprop & BluetoothGattCharacteristic.PROPERTY_NOTIFY)) > 0) {
                                    responseGattChar = bgc;
                                    notifyChars.add(responseGattChar);
                                }
                            } else if (bgc.getUuid().toString().equalsIgnoreCase(hrvBPMCharacteristicUUID)) {
                                if (((chprop & BluetoothGattCharacteristic.PROPERTY_READ) | (chprop & BluetoothGattCharacteristic.PROPERTY_NOTIFY)) > 0) {
                                    bpmGattChar = bgc;
                                    notifyChars.add(bpmGattChar);
                                }
                            } else if (bgc.getUuid().toString().equalsIgnoreCase(hrvLiveSignalCharacteristicUUID)) {
                                if (((chprop & BluetoothGattCharacteristic.PROPERTY_READ) | (chprop & BluetoothGattCharacteristic.PROPERTY_NOTIFY)) > 0) {
                                    liveSignalGattChar = bgc;
                                    notifyChars.add(liveSignalGattChar);
                                }
                            } else if (bgc.getUuid().toString().equalsIgnoreCase(hrvLiveRRCharacteristicUUID)) {
                                if (((chprop & BluetoothGattCharacteristic.PROPERTY_READ) | (chprop & BluetoothGattCharacteristic.PROPERTY_NOTIFY)) > 0) {
                                    liveRRGattChar = bgc;
                                    notifyChars.add(liveRRGattChar);
                                }
                            }
                        }
                    }
                }
            }

            if (hasAllCharacteristics()) {
                subscribeToCharacteristics(gatt);
                Log.i("[BLE]", "CONNECTED and ready to send");
                fireConnected();
            }
        }

        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        private void subscribeToCharacteristics(BluetoothGatt gatt) {
            if (notifyChars.isEmpty()) return;
            BluetoothGattCharacteristic characteristic = notifyChars.get(0);
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }

        @Override
        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i("[BLE]", "Wrote descriptor for characteristic");
            super.onDescriptorWrite(gatt, descriptor, status);
            notifyChars.remove(0);
            subscribeToCharacteristics(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            Log.i("[BLE]", "Characteristic changed: " + characteristic.getUuid().toString() + " Value: " + Arrays.toString(data));
            if (characteristic.getUuid().toString().equalsIgnoreCase(hrvResponseCharacteristicUUID)) {
                double value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getDouble();
                fireHRVDataReceived(value);
            } else if (characteristic.getUuid().toString().equalsIgnoreCase(hrvBPMCharacteristicUUID)) {
                int value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
                fireLiveDataReceived(value, "BPM");
            } else if (characteristic.getUuid().toString().equalsIgnoreCase(hrvLiveSignalCharacteristicUUID)) {
                int value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
                fireLiveDataReceived(value, "SIG");
            } else if (characteristic.getUuid().toString().equalsIgnoreCase(hrvLiveRRCharacteristicUUID)) {
                int value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
                fireLiveDataReceived(value, "RR");
            }
        }
    };

    private void fireDisconnected() {
        for (BLEControllerListener l : this.listeners)
            l.BLEControllerDisconnected();

        this.device = null;
    }

    private void fireConnected() {
        for (BLEControllerListener l : this.listeners)
            l.BLEControllerConnected();
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private void fireDeviceFound(BluetoothDevice device) {
        if (device == null) return;
        for (BLEControllerListener l : this.listeners)
            l.BLEDeviceFound(device.getName().trim(), device.getAddress());
    }

    private void fireHRVDataReceived(double data) {
        for (BLEControllerListener l : this.listeners)
            l.BLEHRVParametersReceived(data);
    }

    private void fireLiveDataReceived(int data, String characteristic) {
        for (BLEControllerListener l : this.listeners)
            l.BLELiveDataReceived(data, characteristic);
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    public void sendCommand(byte [] data) {
        this.cmdGattChar.setValue(data);
        bluetoothGatt.writeCharacteristic(this.cmdGattChar);
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    public boolean checkConnectedState() {
        return this.bluetoothManager.getConnectionState(this.device, GATT) == 2;
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    public void disconnect() {
        this.bluetoothGatt.disconnect();
    }
}
