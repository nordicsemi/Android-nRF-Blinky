package no.nordicsemi.android.blinky

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.blinky.ble.BlinkyManager
import no.nordicsemi.android.blinky.di.beacon
import no.nordicsemi.android.blinky.di.blinky
import no.nordicsemi.android.blinky.di.blinkyState
import no.nordicsemi.android.blinky.di.hrm
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.spec.exception.BlinkyException
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.mock.MockCentralManager
import no.nordicsemi.kotlin.ble.client.android.mock.mock
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Tests of [BlinkyManager] running against the simulated peripherals defined in the `mock`
 * flavor of the app (see `PeripheralSpecs.kt`).
 *
 * The tests use real time, as the manager works on [Dispatchers.IO].
 */
class BlinkyManagerTest {
    private val timeout = 10.seconds

    private lateinit var scope: CoroutineScope
    private lateinit var centralManager: MockCentralManager

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + Job())
        centralManager = CentralManager.mock(
            environment = MockAndroidEnvironment.Api31(
                isBluetoothEnabled = true,
                isBluetoothConnectPermissionGranted = true,
                isBluetoothScanPermissionGranted = true,
                isNeverForLocationFlagSet = true,
            ),
            scope = scope,
        ).apply {
            simulatePeripherals(listOf(blinky, beacon, hrm))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Creates a manager for the simulated peripheral with the given identifier.
     *
     * Simulated peripherals start advertising when scanning, so the peripheral is first scanned
     * for, as the app does.
     */
    private suspend fun managerFor(identifier: String): BlinkyManager {
        val peripheral = centralManager.scan(timeout)
            .first { it.peripheral.identifier == identifier }
            .peripheral
        return BlinkyManager(centralManager, peripheral)
    }

    /**
     * Connects to the simulated Blinky, runs the [test] when the profile is ready and then
     * disconnects.
     */
    private fun withBlinky(test: suspend CoroutineScope.(Blinky.State) -> Unit) = runBlocking {
        withTimeout(timeout) {
            val ready = CompletableDeferred<Blinky.State>()
            val connection = scope.launch {
                managerFor(BLINKY_ID).connect { state ->
                    ready.complete(state)
                    awaitCancellation()
                }
            }
            try {
                test(ready.await())
            } finally {
                connection.cancel()
            }
        }
    }

    @Test
    fun initialState() = withBlinky { state ->
        assertFalse(state.led.value)
        assertFalse(state.button.value)
    }

    @Test
    fun ledOn_writesToPeripheral() = withBlinky { state ->
        // The profile reads the initial LED state in the background after it is ready, which
        // would overwrite a value set immediately.
        delay(INIT_TIME)
        state.led.value = true
        blinkyState.led.first { it }

        state.led.value = false
        blinkyState.led.first { !it }
    }

    @Test
    fun buttonState_isReceivedFromPeripheral() = withBlinky { state ->
        blinkyState.buttonState = true
        state.button.first { it }

        blinkyState.buttonState = false
        state.button.first { !it }
    }

    @Test
    fun buttonClick_emitsPressedEvent() = withBlinky { state ->
        val pressed = async { state.buttonPressed.first() }
        // Give the collector time to subscribe.
        delay(INIT_TIME)

        blinkyState.buttonState = true
        blinkyState.buttonState = false

        assertEquals(Unit, pressed.await())
    }

    @Test
    fun buttonHold_emitsLongPressedEvent() = withBlinky { state ->
        val longPressed = async { state.buttonLongPressed.first() }
        delay(INIT_TIME)

        blinkyState.buttonState = true

        assertEquals(Unit, longPressed.await())
    }

    @Test
    fun deviceWithoutLbs_isNotSupported() {
        runBlocking {
            withTimeout(timeout) {
                assertFailsWith<BlinkyException.NotSupported> {
                    managerFor(HRM_ID).connect { fail("Profile must not be ready") }
                }
            }
        }
    }

    private companion object {
        const val BLINKY_ID = "AA:BB:CC:DD:EE:FF"
        /** Time given to the profile to read the initial state and subscribe. */
        const val INIT_TIME = 500L
        const val HRM_ID = "01:23:45:67:89:AB"
    }
}
