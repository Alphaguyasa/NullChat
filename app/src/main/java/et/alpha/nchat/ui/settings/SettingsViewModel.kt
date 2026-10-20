package et.alpha.nchat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.nchat.data.settings.DisplayNameStore
import et.alpha.nchat.data.wipe.PanicWipe
import et.alpha.nchat.mesh.MeshCoordinator
import et.alpha.nchat.mesh.protocol.Profile
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsState(
    val selfId: String = "",
    /** What is stored and announced now. */
    val savedName: String? = null,
    /** The field's contents, unsaved. */
    val draft: String = "",
) {
    /** The draft as it would be stored: what the user will actually be called. */
    val normalisedDraft: String get() = Profile.normalize(draft)

    val draftBytes: Int get() = normalisedDraft.toByteArray(Charsets.UTF_8).size

    /** Whether saving would change anything. */
    val canSave: Boolean get() = normalisedDraft != (savedName ?: "")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val names: DisplayNameStore,
    private val panicWipe: PanicWipe,
    coordinator: MeshCoordinator,
) : ViewModel() {

    private val draft = MutableStateFlow(names.displayName.value.orEmpty())

    private val _saved = MutableStateFlow(false)

    /** True briefly after a save, for the screen to confirm it. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _wipeFailed = MutableStateFlow(false)

    /** The system refused the wipe; nothing was erased. */
    val wipeFailed: StateFlow<Boolean> = _wipeFailed.asStateFlow()

    private val selfId = coordinator.selfId

    val state: StateFlow<SettingsState> = combine(names.displayName, draft) { saved, text ->
        SettingsState(selfId = selfId, savedName = saved, draft = text)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsState(selfId = selfId, savedName = names.displayName.value, draft = draft.value),
    )

    fun onDraftChange(value: String) {
        draft.value = value
        _saved.value = false
    }

    /**
     * Stores the draft. The coordinator is listening to the store and tells
     * the mesh, so this screen never touches the radio itself.
     */
    fun onSave() {
        viewModelScope.launch {
            val stored = names.set(draft.value)
            // Shows the user the name as it will appear, trimmed and cleaned,
            // rather than leaving what they typed in the box.
            draft.value = stored.orEmpty()
            _saved.value = true
        }
    }

    /**
     * Erases the phone's copy of everything. On success the process is killed
     * by the system, so only a failure ever comes back to this screen.
     */
    fun onWipe() {
        _wipeFailed.value = !panicWipe.wipe()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
