package io.github.jqssun.btgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.jqssun.btgps.iap2.AccessorySession
import io.github.jqssun.btgps.iap2.ControlSessionMessage
import io.github.jqssun.btgps.iap2.IdentificationInformation
import io.github.jqssun.btgps.iap2.SessionFailure
import io.github.jqssun.btgps.iap2.SessionPhase
import io.github.jqssun.btgps.iap2.nmeaId
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.concurrent.thread

@AndroidEntryPoint
class BluetoothGPSService : Service() {
    @Inject lateinit var hub: SessionHub
    @Inject lateinit var store: SettingsStore
    @Inject lateinit var feed: LocationFeed
    @Inject lateinit var softAuth: SoftAuthCerts

    private val running = AtomicBoolean(false)
    @Volatile private var server: BluetoothServerSocket? = null
    private val links = CopyOnWriteArraySet<Link>()
    private val ids = AtomicInteger()
    private var identification = IdentificationInformation()
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "btgps:stream")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _startForeground()
        if (running.compareAndSet(false, true)) {
            thread(name = "rfcomm-accept") { acceptLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun acceptLoop() {
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) return _abort(R.string.status_bluetooth_off)
        if (runBlocking { softAuth.load() } == null) return _abort(R.string.soft_auth_missing)
        identification = runBlocking { store.identification.first() }
        val btName = identification.bluetoothTransportComponent.transportComponentName
        try {
            adapter.name = btName
        } catch (error: SecurityException) {
            hub.log(R.string.log_adapter_name_failed, error.message)
        }
        hub.update { it.copy(running = true, detail = R.string.status_discoverable) }
        _notify()
        hub.log(R.string.log_listening)
        try {
            val listen = adapter.listenUsingInsecureRfcommWithServiceRecord("Wireless iAP", IAP_UUID)
            server = listen
            while (running.get()) Link(listen.accept()).start()
        } catch (error: Exception) {
            if (running.get()) hub.log(R.string.log_listen_failed, error.message)
        } finally {
            stopListening()
            hub.update { it.copy(running = false, detail = R.string.status_stopped) }
        }
    }

    private fun _abort(@StringRes reason: Int) {
        hub.log(reason)
        hub.update { it.copy(running = false, detail = reason) }
        stopSelf()
    }

    private inner class Link(private val socket: BluetoothSocket) {
        private val id = ids.incrementAndGet()
        @SuppressLint("MissingPermission")
        private val name = runCatching { socket.remoteDevice.name }.getOrNull() ?: socket.remoteDevice.address
        private val sink = LocationFeed.Sink()
        private var phase: SessionPhase? = null

        fun start() {
            links += this
            thread(name = "iap2-$id") {
                // scheduler runs on uptime clock
                wakeLock.acquire()
                try {
                    _run()
                } finally {
                    wakeLock.release()
                    links -= this
                }
            }
        }

        fun close() = socket.closeQuietly()

        private fun _run() {
            _log(R.string.log_connected)
            val mac = parseMac(runBlocking { store.mac.first() })
            if (mac == null) _log(R.string.log_mac_missing)
            val auth = softAuth.auth.value
            if (auth == null) {
                _log(R.string.soft_auth_missing)
                close()
                return
            }
            _log(R.string.log_using_identity)
            val session = AccessorySession(
                auth = auth,
                identificationInformation = identification.encode(mac ?: ByteArray(6)),
                reportAuthenticationSerial = ControlSessionMessage.AccessoryAuthenticationSerialNumber in identification.messagesSentByAccessory,
            )
            val output = socket.outputStream
            fun write(packets: List<ByteArray>) = synchronized(output) {
                packets.forEach { output.write(it) }
                output.flush()
            }
            val io = Executors.newSingleThreadScheduledExecutor()
            io.scheduleAtFixedRate({
                try {
                    val streaming = session.streaming
                    if (streaming) feed.attach(sink) else feed.detach(sink)
                    val gps = if (streaming) {
                        val nmea = sink.drain().filter { it.nmeaId in session.requested }
                        session.gpsPackets(nmea).also { if (it.isNotEmpty()) _logNmea(nmea) }
                    } else emptyList()
                    val packets = gps + session.tick(System.currentTimeMillis())
                    if (packets.isNotEmpty()) write(packets)
                    _publish(session)
                } catch (error: Exception) {
                    _log(R.string.log_write_failed, error.message)
                    close()
                }
            }, 0, 250, TimeUnit.MILLISECONDS)
            _read(session, ::write)
            io.shutdownNow()
            close()
            io.awaitTermination(1, TimeUnit.SECONDS)
            feed.detach(sink)
            _log(R.string.log_disconnected)
            hub.update { it.copy(connections = it.connections - id) }
            _notify()
        }

        private fun _read(session: AccessorySession, write: (List<ByteArray>) -> Unit) {
            val input = socket.inputStream
            val buf = ByteArray(2048)
            while (running.get()) {
                val count = try {
                    input.read(buf)
                } catch (error: Exception) {
                    _log(R.string.log_read_failed, error.message)
                    break
                }
                if (count < 0) break
                val outgoing = try {
                    session.receive(buf.copyOf(count))
                } catch (error: Exception) {
                    _log(R.string.log_protocol_error, error.message)
                    break
                }
                if (outgoing.isNotEmpty()) write(outgoing)
                _publish(session)
            }
        }

        @Synchronized
        private fun _publish(session: AccessorySession) {
            val now = session.phase
            if (now == phase) return
            phase = now
            if (now != SessionPhase.DETECT) _log(session.failure?.let(::_describe) ?: getString(now.label))
            if (now == SessionPhase.STREAMING) _log(R.string.log_location_requested, session.requested.joinToString(", "))
            hub.update { it.copy(connections = it.connections + (id to Connection(name, now))) }
            _notify()
        }

        private fun _logNmea(sentences: List<String>) {
            if (hub.nmeaLogging.value) sentences.forEach { _log(R.string.log_sent, it) }
        }

        private fun _log(text: String) = hub.log(R.string.device_status, name, text)

        private fun _log(@StringRes res: Int, vararg args: Any?) = _log(getString(res, *args))
    }

    private fun _describe(failure: SessionFailure): String = when (failure) {
        SessionFailure.Authentication -> getString(R.string.failure_authentication)
        is SessionFailure.Identification ->
            if (failure.params.isEmpty()) getString(R.string.failure_identification)
            else getString(R.string.failure_identification_params, failure.params.joinToString(","))
    }

    private fun stopListening() {
        running.set(false)
        server?.closeQuietly()
        links.forEach(Link::close)
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun _startForeground() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.app_name))
                .build(),
        )
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null), type)
    }

    @Synchronized
    private fun _notify() {
        if (!running.get()) return
        val state = hub.state.value
        val text = if (state.connections.isEmpty()) getString(state.detail)
        else state.connections.values.joinToString(", ") { getString(R.string.device_status, it.name, getString(it.phase.label)) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        val IAP_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
        private const val CHANNEL = "gps"
        private const val NOTIFICATION_ID = 7
    }
}
