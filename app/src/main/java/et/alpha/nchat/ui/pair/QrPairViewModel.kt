package et.alpha.nchat.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.fingerprintWords
import et.alpha.nchat.mesh.protocol.parsePairingUri
import et.alpha.nchat.mesh.protocol.toPairingUri
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What came of the last code the camera read. */
sealed interface ScanOutcome {

    data object Waiting : ScanOutcome

    /** A peer is now verified. */
    data class Paired(val shortId: String, val fingerprint: List<String>) : ScanOutcome

    /** Scanned our own code — harmless, but worth saying rather than ignoring. */
    data object Ourselves : ScanOutcome

    /** A QR code, but not one of ours. */
    data object NotAPairingCode : ScanOutcome
}

data class QrPairState(
    val pairingUri: String = "",
    val fingerprint: List<String> = emptyList(),
    val shortId: String = "",
    val outcome: ScanOutcome = ScanOutcome.Waiting,
)

@HiltViewModel
class QrPairViewModel @Inject constructor(
    private val identity: LocalIdentity,
    private val peers: PeerRepository,
    private val keyDirectory: PublicKeyDirectory,
) : ViewModel() {

    private val _state = MutableStateFlow(
        QrPairState(
            pairingUri = identity.announcement.toPairingUri(),
            fingerprint = fingerprintWords(identity.publicKey),
            shortId = identity.peerId.toHexId(),
        ),
    )
    val state: StateFlow<QrPairState> = _state.asStateFlow()

    /**
     * Handles a code the camera read.
     *
     * Called for every frame that decodes to anything, so it must be cheap and
     * idempotent — the same code will arrive many times a second while it stays
     * in view.
     */
    fun onScanned(text: String) {
        if (_state.value.outcome is ScanOutcome.Paired) return

        val announcement = parsePairingUri(text)
        if (announcement == null) {
            _state.update { it.copy(outcome = ScanOutcome.NotAPairingCode) }
            return
        }

        if (announcement.peerId.contentEquals(identity.peerId)) {
            _state.update { it.copy(outcome = ScanOutcome.Ourselves) }
            return
        }

        pair(announcement)
    }

    fun reset() {
        _state.update { it.copy(outcome = ScanOutcome.Waiting) }
    }

    /**
     * Records a scanned peer as verified.
     *
     * This is the only place `isVerified` is ever set. Scanning is what
     * distinguishes it from an ANNOUNCE arriving over the radio: the user
     * physically looked at the other device's screen, so the key is bound to a
     * person and not merely to whoever was transmitting.
     */
    private fun pair(announcement: Announcement) = viewModelScope.launch {
        val peerHex = announcement.peerId.toHexId()

        keyDirectory.record(announcement)
        peers.recordSeen(peerHex, publicKey = announcement.signingKey)
        peers.markVerified(peerHex)

        _state.update {
            it.copy(
                outcome = ScanOutcome.Paired(
                    shortId = peerHex,
                    fingerprint = fingerprintWords(announcement.signingKey),
                ),
            )
        }
    }
}
