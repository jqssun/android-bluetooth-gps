package io.github.jqssun.btgps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.GpsStatus
import android.location.Location
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jqssun.btgps.iap2.Fix
import io.github.jqssun.btgps.iap2.Sat
import io.github.jqssun.btgps.iap2.fixSentences
import io.github.jqssun.btgps.iap2.gsvSentences
import io.github.jqssun.btgps.iap2.CustomLocation
import io.github.jqssun.btgps.iap2.FixDetail
import io.github.jqssun.btgps.iap2.passthrough
import io.github.jqssun.btgps.iap2.customSentences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class LocationSource(@StringRes val label: Int, @StringRes val summary: Int) {
    Synthesize(R.string.source_synthesize, R.string.source_synthesize_summary),
    Passthrough(R.string.source_passthrough, R.string.source_passthrough_summary),
    Custom(R.string.source_custom, R.string.source_custom_summary),
}

data class FeedState(
    val location: Location? = null,
    val used: Int = 0,
    val inView: Int = 0,
    @StringRes val error: Int? = null,
)

val Location.mslAltitude: Double?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasMslAltitude()) mslAltitudeMeters else null

@Singleton
class LocationFeed @Inject constructor(
    @ApplicationContext private val context: Context,
    store: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lm = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val sinks = CopyOnWriteArraySet<Sink>()
    val source: StateFlow<LocationSource> = store.source.stateIn(scope, SharingStarted.Eagerly, LocationSource.Synthesize)
    private val custom = store.customLocation.stateIn(scope, SharingStarted.Eagerly, CustomLocation())
    private val detail = store.fixDetail.stateIn(scope, SharingStarted.Eagerly, FixDetail())

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    // main thread only
    private var unregister: (() -> Unit)? = null
    private var gpsSats = emptyList<Sat>()
    private var providers = emptyList<String>()
    private var gpsAt = 0L

    private val location = object : LocationListenerCompat {
        override fun onLocationChanged(loc: Location) {
            when (loc.provider) {
                LocationManager.GPS_PROVIDER -> gpsAt = loc.elapsedRealtimeNanos
                LocationManager.NETWORK_PROVIDER -> if (loc.elapsedRealtimeNanos - gpsAt < GPS_FRESH_NS) return
            }
            _state.update { it.copy(location = loc, error = null) }
            if (source.value == LocationSource.Synthesize) _emit(fixSentences(_fix(loc)) + gsvSentences(gpsSats))
        }

        override fun onProviderEnabled(provider: String) = _state.update { it.copy(error = _offError()) }

        override fun onProviderDisabled(provider: String) = _state.update { it.copy(error = _offError()) }
    }

    private val gnss = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
            val all = 0 until status.satelliteCount
            gpsSats = all.filter { status.getConstellationType(it) == GnssStatusCompat.CONSTELLATION_GPS }.map {
                Sat(
                    svid = status.getSvid(it),
                    elev = status.getElevationDegrees(it).roundToInt().coerceIn(0, 90),
                    az = status.getAzimuthDegrees(it).roundToInt().mod(360),
                    snr = status.getCn0DbHz(it).roundToInt().coerceIn(0, 99),
                )
            }
            _state.update { it.copy(used = all.count(status::usedInFix), inView = status.satelliteCount) }
        }
    }

    private val customTick = object : Runnable {
        override fun run() {
            _emit(customSentences(custom.value, detail.value, Instant.now()))
            main.postDelayed(this, 1000)
        }
    }

    init {
        source.onEach { _register() }.launchIn(scope)
    }

    class Sink {
        private val queue = ConcurrentLinkedQueue<String>()
        internal fun push(sentences: List<String>) { queue += sentences }
        internal fun clear() = queue.clear()
        fun drain(): List<String> = generateSequence(queue::poll).toList()
    }

    /* feed runs while any sink is attached */
    @Synchronized
    fun attach(sink: Sink) {
        if (sink in sinks) return
        sink.clear()
        sinks += sink
        if (sinks.size == 1) main.post(::_register)
    }

    @Synchronized
    fun detach(sink: Sink) {
        if (sinks.remove(sink) && sinks.isEmpty()) main.post(::_unregister)
    }

    private fun _emit(sentences: List<String>) = sinks.forEach { it.push(sentences) }

    @SuppressLint("MissingPermission")
    private fun _register() {
        _unregister()
        val src = source.value
        if (sinks.isEmpty()) return
        if (src == LocationSource.Custom) {
            customTick.run()
            unregister = { main.removeCallbacks(customTick) }
            return
        }
        if (!context.granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            _state.value = FeedState(error = R.string.feed_no_permission)
            return
        }
        providers = _providers(src)
        _state.value = FeedState(error = _offError())
        val exec = ContextCompat.getMainExecutor(context)
        providers.forEach { LocationManagerCompat.requestLocationUpdates(lm, it, REQUEST, exec, location) }
        LocationManagerCompat.registerGnssStatusCallback(lm, exec, gnss)
        val removeNmea = if (src == LocationSource.Passthrough) _addNmea() else null
        unregister = {
            LocationManagerCompat.removeUpdates(lm, location)
            LocationManagerCompat.unregisterGnssStatusCallback(lm, gnss)
            removeNmea?.invoke()
        }
    }

    @SuppressLint("InlinedApi")
    private fun _providers(src: LocationSource): List<String> = when {
        src == LocationSource.Passthrough -> listOf(LocationManager.GPS_PROVIDER)
        LocationManagerCompat.hasProvider(lm, LocationManager.FUSED_PROVIDER) -> listOf(LocationManager.FUSED_PROVIDER)
        else -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }.filter { LocationManagerCompat.hasProvider(lm, it) }

    private fun _offError() = R.string.feed_gps_off.takeIf { providers.none(lm::isProviderEnabled) }

    private fun _unregister() {
        unregister?.invoke()
        unregister = null
        sinks.forEach(Sink::clear)
        _state.value = FeedState()
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun _addNmea(): () -> Unit {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val l = OnNmeaMessageListener { msg, _ -> _onNmea(msg) }
            lm.addNmeaListener(l, main)
            return { lm.removeNmeaListener(l) }
        }
        val l = GpsStatus.NmeaListener { _, msg -> _onNmea(msg) }
        lm.addNmeaListener(l)
        return { lm.removeNmeaListener(l) }
    }

    private fun _onNmea(line: String) {
        passthrough(line)?.let { _emit(listOf(it)) }
    }

    /** ellipsoid altitude stands in for MSL below API 34, use user provided value when absent */
    private fun _fix(loc: Location): Fix {
        val msl = loc.mslAltitude
        return Fix(
            time = Instant.ofEpochMilli(loc.time),
            lat = loc.latitude,
            lon = loc.longitude,
            quality = 1,
            sats = _state.value.used.takeIf { it > 0 } ?: detail.value.sats,
            hdop = detail.value.hdop,
            alt = msl ?: loc.altitude,
            geoid = msl?.let { loc.altitude - it },
            knots = loc.speed * KNOTS_PER_MPS,
            course = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
            magVar = null,
            mode = 'A',
        )
    }

    private companion object {
        const val KNOTS_PER_MPS = 1.943844
        const val GPS_FRESH_NS = 3_000_000_000L
        val REQUEST = LocationRequestCompat.Builder(1000)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
    }
}
