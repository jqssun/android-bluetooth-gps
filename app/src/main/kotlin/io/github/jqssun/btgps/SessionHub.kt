package io.github.jqssun.btgps

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jqssun.btgps.iap2.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class Connection(val name: String, val phase: SessionPhase)

data class SessionUiState(
    val running: Boolean = false,
    @StringRes val detail: Int = R.string.status_stopped,
    val connections: Map<Int, Connection> = emptyMap(),
)

val SessionPhase.label: Int
    @StringRes get() = when (this) {
        SessionPhase.DETECT, SessionPhase.NEGOTIATE -> R.string.phase_connecting
        SessionPhase.AUTHENTICATING -> R.string.phase_authenticating
        SessionPhase.IDENTIFYING -> R.string.phase_identifying
        SessionPhase.IDENTIFIED -> R.string.phase_connected
        SessionPhase.STREAMING -> R.string.phase_streaming
        SessionPhase.FAILED -> R.string.phase_failed
    }

@Singleton
class SessionHub @Inject constructor(
    @ApplicationContext private val context: Context,
    store: SettingsStore,
) {
    val nmeaLogging = store.nmeaLogging.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false)
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val logFile = File(context.filesDir, "btgps_logs.txt")
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val logLock = Any()
    private val logList = ArrayDeque<String>()
    private var fileLines = 0

    init {
        val saved = runCatching { logFile.readLines() }.getOrDefault(emptyList())
        logList.addAll(saved.takeLast(MAX_LOGS))
        fileLines = saved.size
        _logs.value = logList.toList()
    }

    fun update(block: (SessionUiState) -> SessionUiState) {
        _state.update(block)
    }

    fun log(@StringRes res: Int, vararg args: Any?) = log(context.getString(res, *args))

    fun log(msg: String) {
        val line = "${LocalTime.now().format(TIME)} $msg"
        synchronized(logLock) {
            logList.addLast(line)
            if (logList.size > MAX_LOGS) logList.removeFirst()
            if (++fileLines > 2 * MAX_LOGS) _rewriteLocked() else runCatching { logFile.appendText("$line\n") }
            _logs.value = logList.toList()
        }
    }

    fun clearLogs() {
        synchronized(logLock) {
            logList.clear()
            fileLines = 0
            runCatching { logFile.delete() }
            _logs.value = emptyList()
        }
    }

    fun exportLogs() {
        val text = synchronized(logLock) { logList.joinToString("\n") }
        val file = File(context.cacheDir, "btgps_logs.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserTitle = context.getString(R.string.export_logs_chooser_title)
        context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun _rewriteLocked() {
        runCatching { logFile.writeText(logList.joinToString("") { "$it\n" }) }
        fileLines = logList.size
    }

    private companion object {
        const val MAX_LOGS = 9999
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
