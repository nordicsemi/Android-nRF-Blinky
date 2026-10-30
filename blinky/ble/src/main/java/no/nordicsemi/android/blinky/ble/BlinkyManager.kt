package no.nordicsemi.android.blinky.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.spec.exception.BlinkyException
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.InvalidAttributeException
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason
import kotlin.coroutines.CoroutineContext

/**
 * A Bluetooth LE implementation of the [Blinky] interface.
 *
 * The manager is responsible for setting up the Bluetooth LE connection to the peripheral
 * with the LED Button Service (LBS), which becomes available once the connection is established.
 *
 * Use [connect] to initiate the connection to the peripheral.
 * @param centralManager The central manager to use to connect to the peripheral.
 * @param peripheral The peripheral.
 * @param context The coroutine context in which the connection is handled, defaults to
 * [Dispatchers.IO]. Tests may pass a test dispatcher to use virtual time.
 */
class BlinkyManager(
    private val centralManager: CentralManager,
    private val peripheral: Peripheral,
    private val context: CoroutineContext = Dispatchers.IO,
): Blinky {

    override suspend fun connect(
        block: suspend CoroutineScope.(Blinky.State) -> Unit,
    ): Unit = withContext(context) {
        // First, install the LBS profile.
        //
        // Note, that in this implementation the profile is installed before creating the connection,
        // but these can be swapped.
        val ledButtonProfile = LedButtonProfileImpl(context, block)
        peripheral.profile(ledButtonProfile)

        // Initiate connection, if not connected already.
        try {
            centralManager.connect(peripheral)
        } catch (_: TimeoutCancellationException) {
            throw BlinkyException.Timeout()
        } catch (e: CancellationException) {
            // Rethrow.
            throw e
        } catch (_: Exception) {
            throw BlinkyException.ConnectionFailed()
        }

        // Keep the coroutine alive until the peripheral disconnects.
        // This method returns the disconnection reason.
        try {
            val reason = peripheral.awaitDisconnection()
            if (reason == Reason.RequiredServiceNotFound) {
                throw BlinkyException.NotSupported()
            }
            if (reason != Reason.Success) {
                throw BlinkyException.LinkLoss()
            }
        } catch (e: CancellationException) {
            // The scope may get canceled when user leaves the screen.
            // In that case, make sure to disconnect.
            // Don't disconnect when services were invalidated, as the profile will be re-launched.
            if (e.cause !is InvalidAttributeException) {
                peripheral.disconnect()
            }
            // Rethrow.
            throw e
        }
    }
}