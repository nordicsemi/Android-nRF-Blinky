package no.nordicsemi.android.blinky.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.spec.BlinkySpec
import no.nordicsemi.kotlin.ble.client.Peripheral
import no.nordicsemi.kotlin.ble.client.Profile
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.client.exception.OperationFailedException
import no.nordicsemi.kotlin.ble.core.exception.BluetoothException
import timber.log.Timber

/**
 * Implementation of the [Blinky.State] interface for the LED Button Service (LBS).
 *
 * @param onReady A callback called when the profile is initialized.
 */
internal class LedButtonProfileImpl(
    private val onReady: suspend CoroutineScope.(Blinky.State) -> Unit,
): Profile.Simple(
    serviceUuid = BlinkySpec.SERVICE_UUID,
    name = "LBS",
), Blinky.State {
    override val led = MutableStateFlow(false)

    private val _button = MutableStateFlow(false)
    override val button = _button.asStateFlow()

    override val buttonPressed: Flow<Unit> = flow {
        /** Flag set when a long press is detected. */
        var isLongPress = false

        // Drop the initial value, button state is a StateFlow.
        button.drop(1).collect { pressed ->
            // If the button was pressed, start a timeout to detect a long press events.
            if (pressed) {
                try {
                    withTimeout(BlinkySpec.LONG_PRESS_TIMEOUT) {
                        // Await the button to be released before the timeout.
                        button.drop(1).first { !it }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Button has not been released before the time runed out.
                    isLongPress = true
                }
            } else {
                // If released, emit the event only if it was not a long press.
                if (!isLongPress) {
                    emit(Unit)
                }
                isLongPress = false
            }
        }
    }

    override val buttonLongPressed: Flow<Unit> = button
        .flatMapLatest { pressed ->
            if (pressed) flow {
                delay(BlinkySpec.LONG_PRESS_TIMEOUT)
                emit(Unit)
            } else emptyFlow()
        }

    // GATT profile implementation

    /**
     * The GATT characteristics of the LED Button Service (LBS) for controlling the LED state on
     * the remote peripheral.
     *
     * Possible values are:
     * * 0x00 - LED is off.
     * * 0x01 - LED is on.
     * @see BlinkySpec.LED_CHARACTERISTIC_UUID
     */
    private lateinit var ledCharacteristic: RemoteCharacteristic

    /**
     * The GATT characteristics of the LED Button Service (LBS) notified when the Button state on
     * the remote peripheral changes.
     *
     * Possible values are:
     * * 0x00 - Button is released.
     * * 0x01 - Button is pressed.
     * @see BlinkySpec.BUTTON_CHARACTERISTIC_UUID
     */
    private lateinit var buttonCharacteristic: RemoteCharacteristic

    override fun prepare(peripheral: Peripheral<*, *>, service: RemoteService) {
        require(service.uuid == BlinkySpec.SERVICE_UUID) {
            "Unrecognized service UUID: ${service.uuid}"
        }

        ledCharacteristic = service.characteristics
            .first { it.uuid == BlinkySpec.LED_CHARACTERISTIC_UUID }
        buttonCharacteristic = service.characteristics
            .first { it.uuid == BlinkySpec.BUTTON_CHARACTERISTIC_UUID }

        require(ledCharacteristic.isWritable()) {
            "LED characteristic must have WRITE or WRITE WITHOUT RESPONSE property"
        }
        require(buttonCharacteristic.isSubscribable()) {
            "Button characteristic must have NOTIFY or INDICATE property"
        }
    }

    override suspend fun CoroutineScope.initialize(peripheral: Peripheral<*, *>) {
        // Read the initial state of the LED and Button and start the collectors in
        // a separate coroutine.
        launch(Dispatchers.IO) {
            readLed()
            readButton()
            initLedStateCollector()
            initButtonStateCollector()
        }
        // Notify the app, that the profile is ready.
        onReady(this@LedButtonProfileImpl)
    }

    // Helper methods

    /**
     * Reads the initial LED state from the characteristic and updates the [led] flow.
     */
    private suspend fun readLed() {
        try {
            // Read initial LED state from the characteristic.
            val rawLedValue = ledCharacteristic.read()
            val ledValue = rawLedValue.state

            // Update the local state.
            led.update { ledValue }
        } catch (_: InvalidAttributeException) {
            // This exception is thrown when the device disconnects, or invalidates services.
            Timber.w("Services invalidated before reading from LED characteristic")
        } catch (e: OperationFailedException) {
            // In some implementations the Button characteristic is not readable.
            Timber.w("Reading LED characteristic failed: ${e.message}")
        } catch (e: BluetoothException) {
            // Other errors.
            Timber.e(e, "Reading LED characteristic failed")
            throw e
        }
    }

    /**
     * Reads the initial Button state from the characteristic and updates the [button] flow.
     */
    private suspend fun readButton() {
        try {
            // Read initial state from the characteristic.
            val rawButtonValue = buttonCharacteristic.read()
            val pressed = rawButtonValue.state

            // Update the local state.
            _button.update { pressed }
        } catch (_: InvalidAttributeException) {
            // This exception is thrown when the device disconnects, or invalidates services.
            Timber.w("Services invalidated before reading from button characteristic")
        } catch (e: OperationFailedException) {
            // In some implementations the Button characteristic is not readable.
            Timber.w("Reading button characteristic failed: ${e.message}")
        } catch (e: BluetoothException) {
            Timber.e(e, "Reading button characteristic failed: ${e.message}")
            throw e
        }
    }

    /**
     * Initializes the observer of the LED state flow.
     *
     * Whenever the state of the [led] flow changes, a remote device is updated by writing to
     * the characteristic.
     */
    private fun CoroutineScope.initLedStateCollector() {
        launch {
            // Whenever the value changes, write the value to the characteristic.
            led.collect { value ->
                try {
                    val command = byteArrayOf(if (value) 1 else 0)
                    ledCharacteristic.write(command)
                } catch (_: InvalidAttributeException) {
                    // This exception is thrown when the device disconnects, or invalidates services.
                    Timber.w("Services invalidated before writing to LED characteristic")
                } catch (e: OperationFailedException) {
                    Timber.w("Writing to LED characteristic failed: ${e.message}")
                } catch (e: BluetoothException) {
                    // Other errors.
                    Timber.e(e, "Writing to LED characteristic failed")
                }
            }
        }
    }

    /**
     * Initializes the observer of the Button characteristic that will emit updates to the
     * [button] flow.
     */
    private fun CoroutineScope.initButtonStateCollector() {
        launch {
            try {
                buttonCharacteristic.subscribe()
                    .collect { value -> _button.update { value.state } }
            } catch (_: InvalidAttributeException) {
                // This exception is thrown when the device disconnects, or invalidates services.
                Timber.w("Services invalidated before subscribing to button characteristic")
            } catch (e: OperationFailedException) {
                Timber.w("Subscribing to button characteristic failed: ${e.message}")
            } catch (e: BluetoothException) {
                // Other errors.
                Timber.e("Subscribing to button characteristic failed: ${e.message}")
            }
        }
    }

    /**
     * Parses the raw value of LED and Button (0x00 or 0x01) to [Boolean].
     *
     * Note: In LED Button Service both LED and Button use the same state encoding.
     */
    private val ByteArray.state: Boolean
        get() = singleOrNull() == 1.toByte()
}