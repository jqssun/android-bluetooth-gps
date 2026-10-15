package io.github.jqssun.btgps

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jqssun.btgps.iap2.SessionPhase
import io.github.jqssun.btgps.iap2.CustomLocation
import io.github.jqssun.btgps.iap2.FixDetail

@Composable
fun SessionScreen(
    state: SessionUiState,
    source: LocationSource,
    feed: FeedState,
    onSource: (LocationSource) -> Unit,
    custom: CustomLocation,
    detail: FixDetail,
    onSaveLocation: (CustomLocation, FixDetail) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phases = state.connections.values.map { it.phase }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine(
                    if (state.running) R.drawable.bluetooth_searching_24px else R.drawable.close_24px,
                    stringResource(state.detail),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.typography.headlineSmall,
                )
                state.connections.values.forEach {
                    StatusLine(
                        it.phase.icon,
                        stringResource(R.string.device_status, it.name, stringResource(it.phase.label)),
                        it.phase.color(),
                        MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(stringResource(R.string.session_hint), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onStart, shapes = ButtonDefaults.shapes(), enabled = !state.running) {
                        Text(stringResource(R.string.btn_discoverable))
                    }
                    Button(onClick = onStop, shapes = ButtonDefaults.shapes(), enabled = state.running) {
                        Text(stringResource(R.string.btn_stop))
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleMedium)
                ConnectedPicker(LocationSource.entries, source, { it.label }, onSource)
                Text(
                    stringResource(source.summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (source != LocationSource.Passthrough) FixFields(source == LocationSource.Custom, custom, detail, onSaveLocation)
                if (source != LocationSource.Custom && state.running && phases.any { it in CONNECTED }) {
                    DeviceFix(feed, requested = SessionPhase.STREAMING in phases)
                }
            }
        }
    }
}

@Composable
private fun FixFields(position: Boolean, loc: CustomLocation, detail: FixDetail, onSave: (CustomLocation, FixDetail) -> Unit) {
    var lat by rememberSaveable(loc) { mutableStateOf(loc.lat.toString()) }
    var lon by rememberSaveable(loc) { mutableStateOf(loc.lon.toString()) }
    var alt by rememberSaveable(loc) { mutableStateOf(loc.alt.toString()) }
    var sats by rememberSaveable(detail) { mutableStateOf(detail.sats.toString()) }
    var hdop by rememberSaveable(detail) { mutableStateOf(detail.hdop.toString()) }

    val draftLoc = if (position) CustomLocation(_num(lat), _num(lon), _num(alt)) else loc
    val draftDetail = FixDetail(sats.trim().toIntOrNull() ?: -1, _num(hdop))
    if (position) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberInput(R.string.custom_lat, lat, draftLoc.latOk, Modifier.weight(1f)) { lat = it }
            NumberInput(R.string.custom_lon, lon, draftLoc.lonOk, Modifier.weight(1f)) { lon = it }
        }
        NumberInput(R.string.custom_alt, alt, draftLoc.altOk, Modifier.fillMaxWidth()) { alt = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberInput(R.string.fix_sats, sats, draftDetail.satsOk, Modifier.weight(1f), KeyboardType.Number) { sats = it }
        NumberInput(R.string.fix_hdop, hdop, draftDetail.hdopOk, Modifier.weight(1f)) { hdop = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { onSave(draftLoc, draftDetail) },
            shapes = ButtonDefaults.shapes(),
            enabled = draftLoc.valid && draftDetail.valid && (draftLoc != loc || draftDetail != detail),
        ) {
            Text(stringResource(R.string.btn_save))
        }
        OutlinedButton(
            onClick = {
                if (position) CustomLocation().let { lat = it.lat.toString(); lon = it.lon.toString(); alt = it.alt.toString() }
                FixDetail().let { sats = it.sats.toString(); hdop = it.hdop.toString() }
            },
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.btn_reset))
        }
    }
}

@Composable
private fun NumberInput(
    @StringRes label: Int,
    value: String,
    ok: Boolean,
    modifier: Modifier,
    keyboard: KeyboardType = KeyboardType.DecimalSigned,
    onValue: (String) -> Unit,
) = Field(label, value, R.string.error_invalid.takeUnless { ok }, modifier, keyboard = keyboard, onValue = onValue)

private fun _num(text: String) = text.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN

private val CONNECTED = setOf(SessionPhase.IDENTIFIED, SessionPhase.STREAMING)

@Composable
private fun DeviceFix(feed: FeedState, requested: Boolean) {
    val fix = feed.location.takeIf { requested && feed.error == null }
    when {
        !requested -> StatusLine(
            icon = R.drawable.hourglass_empty_24px,
            text = stringResource(R.string.feed_waiting_request),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        feed.error != null -> StatusLine(
            icon = if (feed.error == R.string.feed_gps_off) R.drawable.location_off_24px else R.drawable.wrong_location_24px,
            text = stringResource(feed.error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        fix == null -> StatusLine(
            icon = R.drawable.location_searching_24px,
            text = stringResource(R.string.feed_waiting_fix),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )        else -> StatusLine(
            icon = R.drawable.my_location_24px,
            text = stringResource(R.string.feed_fix),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (requested && feed.inView > 0) {
        Text(stringResource(R.string.feed_sats, feed.used, feed.inView), style = MaterialTheme.typography.bodyMedium)
    }
    if (fix != null) {
        Text(stringResource(R.string.feed_lat, fix.latitude), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.feed_lon, fix.longitude), style = MaterialTheme.typography.bodyMedium)
        if (fix.hasAltitude()) {
            Text(stringResource(R.string.feed_alt, fix.mslAltitude ?: fix.altitude), style = MaterialTheme.typography.bodyMedium)
        }
        if (fix.hasAccuracy()) {
            Text(stringResource(R.string.feed_acc, fix.accuracy), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatusLine(
    @DrawableRes icon: Int,
    text: String,
    color: Color,
    style: TextStyle,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color)
        Text(text, style = style, color = color)
    }
}

private val SessionPhase.icon: Int
    @DrawableRes get() = when (this) {
        SessionPhase.DETECT -> R.drawable.bluetooth_searching_24px
        SessionPhase.NEGOTIATE -> R.drawable.sync_24px
        SessionPhase.AUTHENTICATING -> R.drawable.lock_24px
        SessionPhase.IDENTIFYING -> R.drawable.badge_24px
        SessionPhase.IDENTIFIED -> R.drawable.check_circle_fill1_24px
        SessionPhase.STREAMING -> R.drawable.location_on_fill1_24px
        SessionPhase.FAILED -> R.drawable.error_fill1_24px
    }

@Composable
private fun SessionPhase.color(): Color = when (this) {
    SessionPhase.DETECT -> MaterialTheme.colorScheme.onSurfaceVariant
    SessionPhase.NEGOTIATE,
    SessionPhase.AUTHENTICATING,
    SessionPhase.IDENTIFYING,
    -> MaterialTheme.colorScheme.tertiary
    SessionPhase.IDENTIFIED -> MaterialTheme.colorScheme.secondary
    SessionPhase.STREAMING -> MaterialTheme.colorScheme.primary
    SessionPhase.FAILED -> MaterialTheme.colorScheme.error
}
