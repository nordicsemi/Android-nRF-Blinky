package no.nordicsemi.android.blinky.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.android.blinky.spec.BlinkySpec
import no.nordicsemi.kotlin.ble.client.mock.ConnectionResult
import no.nordicsemi.kotlin.ble.client.mock.DisconnectionReason
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.mock.ServiceDiscoveryResult
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Handles for characteristics allow to distinguish them when handling events.

/** State of the Blinky device. */
interface BlinkyState {
    /** Current state of the LED. */
    val led: StateFlow<Boolean>
    /** The current state of the Button. */
    var buttonState: Boolean
}

/** State of the Blinky device. */
val blinkyState: BlinkyState = BlinkyImpl

/**
 * Implementation of a peripheral with LED Button Service (LBS), called Blinky.
 *
 * This is based on the [Peripheral LBS](https://docs.nordicsemi.com/bundle/ncs-latest/page/nrf/samples/bluetooth/peripheral_lbs/README.html)
 * (LED Button Service) from Nordic SDK.
 *
 * The device has one characteristic for the Button (read/notify) and one for the LED (read/write).
 */
private object BlinkyImpl: PeripheralSpecEventHandler, BlinkyState {
    /** Checks whether the byte array represents "ON" state. */
    private fun ByteArray.isOn() = isNotEmpty() && this[0] != 0.toByte()
    /** Converts the Boolean to a byte array. */
    private fun Boolean.toBytes(): ByteArray =
        if (this) byteArrayOf(0x01) else byteArrayOf(0x00)

    /** Handle of the Button characteristic. */
    var buttonHandle: Int? = null

    /** Handle of the LED characteristic. */
    var ledHandle: Int? = null

    // State
    private val _ledState = MutableStateFlow(false)
    override val led = _ledState.asStateFlow()
    override var buttonState = false
        set(value) {
            field = value
            Timber.i("[Blinky] Button pressed: $value")
            buttonHandle?.let { handle ->
                blinky.simulateValueUpdate(handle, value.toBytes())
            }
        }

    // Event handlers implementation

    override fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy): ConnectionResult {
        Timber.i("[Blinky] Connection request received")
        return ConnectionResult.Accept
    }

    override fun onConnectionLost(peripheral: MockPeripheral, reason: DisconnectionReason) {
        Timber.i("[Blinky] Connection terminated: $reason")
    }

    override fun onReset(peripheral: MockPeripheral) {
        Timber.i("[Blinky] --- Booting up ---")
        _ledState.update { false }
        buttonState = false
    }

    override fun onServiceDiscoveryRequest(
        peripheral: MockPeripheral,
        uuids: List<Uuid>,
    ): ServiceDiscoveryResult {
        Timber.i("[Blinky] Service discovery requested for UUIDs: $uuids")
        return super.onServiceDiscoveryRequest(peripheral, uuids)
    }

    override fun onWriteRequest(
        peripheral: MockPeripheral,
        characteristic: MockRemoteCharacteristic,
        value: ByteArray
    ): WriteResponse {
        val on = value.isOn()
        _ledState.update { on }
        Timber.i("[Blinky] LED ${if (on) "ON" else "OFF"}")
        return WriteResponse.Success
    }

    override fun onWriteCommand(
        peripheral: MockPeripheral,
        characteristic: MockRemoteCharacteristic,
        value: ByteArray,
    ) {
        val _ = onWriteRequest(peripheral, characteristic, value)
    }

    override fun onReadRequest(
        peripheral: MockPeripheral,
        characteristic: MockRemoteCharacteristic,
    ): ReadResponse =
        when (characteristic.instanceId) {
            buttonHandle -> ReadResponse.Success(buttonState.toBytes())
            ledHandle -> ReadResponse.Success(led.value.toBytes())
            else -> ReadResponse.Failure(OperationStatus.ReadNotPermitted)
        }
}

/** Definition of the Blinky device. */
val blinky = PeripheralSpec
    .simulatePeripheral(
        identifier = "AA:BB:CC:DD:EE:FF",
        proximity = Proximity.NEAR,
    ) {
        // The mock device will advertise using LBS Service with 0.5 second intervals.
        // It will start advertising after a second.
        advertising(
            parameters = LegacyAdvertisingSetParameters(
                connectable = true,
                interval = 500.milliseconds,
            ),
            isAdvertisingWhenConnected = false,
            delay = 1.seconds,
            // timeout = 10.seconds,
            // maxAdvertisingEvents = 30,
        ) {
            CompleteLocalName("Mock_LBS")
            ServiceUuid(BlinkySpec.SERVICE_UUID)
        }
        // Define it as connectable and set up the event handler and GATT table.
        connectable(
            // Note:
            // The advertised name can be different from the device name.
            name = "Mock_LBS",
            maxAttMtu = 247,
            maxL2capMtu = 251,
            // Event handler is responsible for handling GATT requests.
            eventHandler = BlinkyImpl,
            // Note:
            // Uncommenting this line switches to a different "connectable" method, which
            // makes the peripheral "cached" (there's additional param "cachedServices" to provide).
            // In that case, the mock impl assumes, that the peripheral was connected before
            // and services were cached, i.e. the Device Name was read. Hence, the scanner
            // will switch from "Nordic_LBS" to "Nordic_Blinky".
            //
            // isBonded = false,
        ) {
            GenericAccessService()
            GenericAttributeService()
            // Add LED Button Service (Blinky)
            Service(
                uuid = BlinkySpec.SERVICE_UUID,
            ) {
                BlinkyImpl.buttonHandle = Characteristic(
                    uuid = BlinkySpec.BUTTON_CHARACTERISTIC_UUID,
                    properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
                    permission = Permission.READ,
                )
                BlinkyImpl.ledHandle = Characteristic(
                    uuid = BlinkySpec.LED_CHARACTERISTIC_UUID,
                    properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                    permissions = Permission.READ and Permission.WRITE,
                )
            }
        }
    }

/**
 * A definition of another device, which advertises as an Eddystone beacon.
 *
 * This will not be scannable for the device, as it is using 'neverForLocation' flag (using API 31+),
 * but will be scannable on older environments.
 *
 * @see MockAndroidEnvironment
 */
val beacon = PeripheralSpec.simulatePeripheral(
    identifier = "11:22:33:44:55:66",
    proximity = Proximity.FAR,
) {
    advertising(
        parameters = LegacyAdvertisingSetParameters(
            connectable = true,
            interval = 1.seconds,
        ),
        // Note:
        // Beacons are excluded from scan results if "neverForLocation" flag is disabled.
        isBeacon = true,
    ) {
        CompleteLocalName("Nordic_Beacon")
        ServiceUuid(shortUuid = 0xFEAA) // Eddystone UUID
        IncludeTxPowerLevel()
    }
}

/**
 * The HRM device will advertise as well, but the app should show it as unsupported.
 *
 * Note, that with the default filter, the scanner will not show this device. Tap the Filter icon
 * on the scanner page and disable "Type".
 */
val hrm = PeripheralSpec.simulatePeripheral(
    identifier = "01:23:45:67:89:AB",
    proximity = Proximity.NEAR,
) {
    advertising(
        parameters = Bluetooth5AdvertisingSetParameters(
            connectable = true,
            interval = 1.seconds,
        ),
    ) {
        CompleteLocalName("Nordic_HRM")
        ServiceUuid(shortUuid = 0x180D) // Heart Rate Service
        ServiceUuid(shortUuid = 0x180F) // Battery Service
    }
    connectable(
        name = "Nordic_HRM",
        maxAttMtu = 498,
        maxL2capMtu = 251,
        eventHandler = object : PeripheralSpecEventHandler {
            override fun onConnectionRequest(
                peripheral: MockPeripheral,
                phy: PrimaryPhy,
            ): ConnectionResult {
                Timber.i("[HRM] Connection request received")
                return super.onConnectionRequest(peripheral, phy)
            }

            override fun onConnectionLost(
                peripheral: MockPeripheral,
                reason: DisconnectionReason,
            ) {
                Timber.i("[HRM] Connection terminated: $reason")
                super.onConnectionLost(peripheral, reason)
            }
        },
    ) {
        GenericAccessService()
        GenericAttributeService()
        Service(
            shortUuid = 0x180D, // Heart Rate Service
        ) {
            Characteristic(
                shortUuid = 0x2A37, // Heart Rate Measurement
                property = CharacteristicProperty.NOTIFY,
            )
            Characteristic(
                shortUuid = 0x2A38, // Body Sensor Location
                property = CharacteristicProperty.READ,
                permission = Permission.READ,
            )
            Characteristic(
                shortUuid = 0x2A39, // Heart Rate Control Point
                property = CharacteristicProperty.WRITE,
                permission = Permission.WRITE,
            )
        }
    }
}