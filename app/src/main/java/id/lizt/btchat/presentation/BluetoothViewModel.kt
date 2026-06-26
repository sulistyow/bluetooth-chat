package id.lizt.btchat.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.lizt.btchat.domain.chat.BluetoothController
import id.lizt.btchat.domain.chat.BluetoothDeviceDomain
import id.lizt.btchat.domain.chat.ConnectionResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val bluetoothController: BluetoothController
) : ViewModel() {

    companion object {
        private const val TAG = "BluetoothViewModel"
    }

    private val _state = MutableStateFlow(BluetoothUiState())

    // State yang di-observe di Compose
    val state = combine(
        bluetoothController.scannedDevices,
        bluetoothController.pairedDevices,
        _state
    ) { scannedDevices, pairedDevices, state ->
        state.copy(
            scannedDevices = scannedDevices,
            pairedDevices = pairedDevices,
            // Untuk sementara, jangan kosongkan messages saat disconnect,
            // supaya gampang debug. Kalau mau, nanti bisa balikin ke:
            // messages = if (state.isConnected) state.messages else emptyList()
            messages = state.messages
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        _state.value
    )

    private var deviceConnectionJob: Job? = null

    init {
        // Observe flag isConnected dari controller
        bluetoothController.isConnected
            .onEach { isConnected ->
                Log.d(TAG, "isConnected flow from controller = $isConnected")
                _state.update { it.copy(isConnected = isConnected) }
            }
            .launchIn(viewModelScope)

        // Observe error dari controller
        bluetoothController.errors
            .onEach { error ->
                Log.e(TAG, "Error from controller = $error")
                _state.update { it.copy(errorMessage = error) }
            }
            .launchIn(viewModelScope)
    }

    fun connectToDevice(device: BluetoothDeviceDomain) {
        Log.d(TAG, "connectToDevice: $device")
        _state.update {
            it.copy(
                isConnecting = true,
                errorMessage = null
            )
        }

        deviceConnectionJob?.cancel()
        deviceConnectionJob = bluetoothController
            .connectToDevice(device)
            .listen()
    }

    fun disconnectFromDevice() {
        Log.d(TAG, "disconnectFromDevice")
        deviceConnectionJob?.cancel()
        bluetoothController.closeConnection()
        _state.update {
            it.copy(
                isConnecting = false,
                isConnected = false
            )
        }
    }

    fun waitForIncomingConnections() {
        Log.d(TAG, "waitForIncomingConnections")
        _state.update {
            it.copy(
                isConnecting = true,
                errorMessage = null
            )
        }

        deviceConnectionJob?.cancel()
        deviceConnectionJob = bluetoothController
            .startBluetoothServer()
            .listen()
    }

    fun sendMessage(message: String) {
        Log.d(TAG, "sendMessage: $message")
        viewModelScope.launch {
            val bluetoothMessage = bluetoothController.trySendMessage(message)
            if (bluetoothMessage != null) {
                _state.update {
                    it.copy(
                        messages = it.messages + bluetoothMessage
                    )
                }
            }
        }
    }

    fun startScan() {
        Log.d(TAG, "startScan")
        bluetoothController.startDiscovery()
    }

    fun stopScan() {
        Log.d(TAG, "stopScan")
        bluetoothController.stopDiscovery()
    }

    private fun Flow<ConnectionResult>.listen(): Job {
        return onEach { result ->
            Log.d(TAG, "ConnectionResult = $result")
            when (result) {
                ConnectionResult.ConnectionEstablished -> {
                    Log.d(TAG, "Connection established")
                    _state.update {
                        it.copy(
                            isConnected = true,
                            isConnecting = false,
                            errorMessage = null
                        )
                    }
                }

                is ConnectionResult.TransferSucceeded -> {
                    Log.d(TAG, "Message received: ${result.message}")
                    _state.update {
                        it.copy(
                            messages = it.messages + result.message
                        )
                    }
                }

                is ConnectionResult.Error -> {
                    Log.e(TAG, "Connection error: ${result.message}")
                    _state.update {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
            .catch { throwable ->
                Log.e(TAG, "listen() catch: ${throwable.message}", throwable)
                bluetoothController.closeConnection()
                _state.update {
                    it.copy(
                        isConnected = false,
                        isConnecting = false
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared -> release controller")
        bluetoothController.release()
    }
}
