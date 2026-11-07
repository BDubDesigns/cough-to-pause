package dev.harness.coughtopause.platform

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Reads the *actual* device set, not what the operator hopes is connected.
 *
 * The microphone route matters for this experiment: earbud microphones produce a very
 * different cough signal from the phone microphone, and TTS playback quality depends on
 * whether the output is A2DP (media, good) or SCO (call, narrow band).
 */
class AudioRouteMonitor(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun snapshot(recordRateHz: Int, aecRequested: Boolean, aecActive: Boolean): RouteInfo {
        val manager = audioManager ?: return RouteInfo(
            inputName = "no AudioManager",
            outputName = "no AudioManager",
            sampleRateHz = recordRateHz,
            aecRequested = aecRequested,
            aecActive = aecActive,
        )
        val inputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrDefault(emptyArray())
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())

        val input = pickInput(inputs)
        val output = pickOutput(outputs)
        return RouteInfo(
            inputName = input?.let { "${typeName(it.type)} (${it.productName})" } ?: "default mic",
            outputName = output?.let { "${typeName(it.type)} (${it.productName})" } ?: "default output",
            isBluetoothInput = input?.let { isBluetoothType(it.type) } ?: false,
            isBluetoothOutput = output?.let { isBluetoothType(it.type) } ?: false,
            sampleRateHz = recordRateHz,
            aecRequested = aecRequested,
            aecActive = aecActive,
        )
    }

    private fun pickInput(devices: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val preference = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
        )
        for (type in preference) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return devices.firstOrNull()
    }

    private fun pickOutput(devices: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val preference = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        for (type in preference) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return devices.firstOrNull()
    }

    private fun isBluetoothType(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        type == AudioDeviceInfo.TYPE_BLE_BROADCAST

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        else -> "type$type"
    }
}
