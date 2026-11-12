package et.alpha.nchat.mesh.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The scan windows of one radio-ready stretch: advertise, then discover in
 * bursts on the [dutyCycle]'s schedule.
 *
 * Advertising is meant to start once and stay up (see [Transport.stopDiscovery]).
 * It used to be tried once per stretch, so a single radio error left the phone
 * invisible until the radio went away and came back, which can be all day.
 * Now a failed start is tried again at the top of every scan window until it
 * succeeds; once it is up it is left alone.
 *
 * Kept free of Android so the schedule is tested on the JVM with virtual time.
 */
class ScanLoop(
    private val transport: Transport,
    private val dutyCycle: DutyCycle,
    private val log: (String) -> Unit = {},
) {

    /** Runs scan windows until [isReady] turns false or the caller is cancelled. */
    suspend fun runWhileReady(isReady: () -> Boolean) {
        var advertising = false
        while (currentCoroutineContext().isActive && isReady()) {
            if (!advertising) {
                advertising = runCatching { transport.startAdvertising() }
                    .onFailure { log("advertising failed to start, retrying next window: ${it.message}") }
                    .isSuccess
            }

            val backedOff = dutyCycle.isBackedOff()
            runCatching { transport.startDiscovery() }
                .onFailure { log("discovery failed to start: ${it.message}") }

            delay(dutyCycle.scanMillis)

            runCatching { transport.stopDiscovery() }
                .onFailure { log("stopDiscovery failed: ${it.message}") }

            val idle = dutyCycle.idleMillis()
            log("scan window closed, idling ${idle}ms (backedOff=$backedOff, advertising=$advertising)")
            delay(idle)
        }
    }
}
