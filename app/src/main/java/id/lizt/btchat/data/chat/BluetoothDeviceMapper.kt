package id.lizt.btchat.data.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import id.lizt.btchat.domain.chat.BluetoothDeviceDomain

@SuppressLint("MissingPermission")
fun BluetoothDevice.toBluetoothDeviceDomain():BluetoothDeviceDomain{
    return BluetoothDeviceDomain(
        name = name,
        address = address
    )
}