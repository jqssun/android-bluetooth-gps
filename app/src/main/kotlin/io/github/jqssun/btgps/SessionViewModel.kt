package io.github.jqssun.btgps

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jqssun.btgps.iap2.IdentificationInformation
import io.github.jqssun.btgps.iap2.CustomLocation
import io.github.jqssun.btgps.iap2.FixDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SoftAuthResult {
    data object Imported : SoftAuthResult
    data class Failed(val reason: String) : SoftAuthResult
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val hub: SessionHub,
    private val store: SettingsStore,
    private val softAuth: SoftAuthCerts,
    feed: LocationFeed,
) : ViewModel() {
    val state: StateFlow<SessionUiState> = hub.state
    val savedMac = store.mac.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val identification = store.identification
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IdentificationInformation())
    val source = feed.source
    val customLocation = store.customLocation.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomLocation())
    val fixDetail = store.fixDetail.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FixDetail())
    val feed = feed.state
    val logs = hub.logs
    val softAuthImported = softAuth.auth.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val nmeaLogging = hub.nmeaLogging
    val developer = store.developer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val softAuthApkUrl = store.softAuthApkUrl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DEFAULT_SOFT_AUTH_APK_URL,
    )
    private val _softAuthBusy = MutableStateFlow(false)
    val softAuthBusy: StateFlow<Boolean> = _softAuthBusy.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Long?>(null)
    val downloadProgress: StateFlow<Long?> = _downloadProgress.asStateFlow()
    private val _softAuthResult = MutableSharedFlow<SoftAuthResult>(extraBufferCapacity = 1)
    val softAuthResult = _softAuthResult.asSharedFlow()

    fun saveIap2(identification: IdentificationInformation, mac: String) {
        viewModelScope.launch { store.setIdentification(identification, mac) }
    }

    fun setSource(value: LocationSource) {
        viewModelScope.launch { store.setSource(value) }
    }

    fun saveLocation(custom: CustomLocation, detail: FixDetail) {
        viewModelScope.launch { store.setLocation(custom, detail) }
    }

    fun setDeveloper(value: Boolean) {
        viewModelScope.launch { store.setDeveloper(value) }
    }

    fun setNmeaLogging(value: Boolean) {
        viewModelScope.launch { store.setNmeaLogging(value) }
    }

    fun saveSoftAuthApkUrl(value: String) {
        viewModelScope.launch { store.setSoftAuthApkUrl(value) }
    }

    fun downloadSoftAuth(url: String) {
        runImport {
            _downloadProgress.value = 0
            store.setSoftAuthApkUrl(url)
            softAuth.downloadAndImport(url) { _downloadProgress.value = it }
        }
    }

    fun importSoftAuth(uri: Uri) = runImport { softAuth.import(uri) }

    private fun runImport(block: suspend () -> Unit) {
        if (!_softAuthBusy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                hub.log(R.string.soft_auth_imported)
                _softAuthResult.emit(SoftAuthResult.Imported)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val reason = error.message ?: error.javaClass.simpleName
                hub.log(R.string.soft_auth_import_failed, reason)
                _softAuthResult.emit(SoftAuthResult.Failed(reason))
            } finally {
                _downloadProgress.value = null
                _softAuthBusy.value = false
            }
        }
    }

    fun clearLogs() = hub.clearLogs()

    fun exportLogs() = hub.exportLogs()
}
