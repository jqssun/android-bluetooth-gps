package io.github.jqssun.btgps

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(onIap2: () -> Unit, viewModel: SessionViewModel, modifier: Modifier = Modifier) {
    val imported by viewModel.softAuthImported.collectAsStateWithLifecycle()
    val savedUrl by viewModel.softAuthApkUrl.collectAsStateWithLifecycle()
    val busy by viewModel.softAuthBusy.collectAsStateWithLifecycle()
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val nmeaLogging by viewModel.nmeaLogging.collectAsStateWithLifecycle()
    val developer by viewModel.developer.collectAsStateWithLifecycle()
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importSoftAuth(uri)
    }
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var urlFocused by remember { mutableStateOf(false) }
    fun committedUrl() = softAuthApkUrl(url).also { url = it }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.soft_auth_title),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    painterResource(if (imported) R.drawable.check_circle_fill1_24px else R.drawable.error_fill1_24px),
                    null,
                    Modifier.size(48.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(if (imported) R.string.soft_auth_imported else R.string.soft_auth_missing),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(if (imported) R.string.soft_auth_detail_imported else R.string.soft_auth_detail_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Button(
            onClick = { viewModel.downloadSoftAuth(committedUrl()) },
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
            enabled = !busy,
        ) {
            Text(
                when (val bytes = progress) {
                    null -> stringResource(R.string.soft_auth_download)
                    0L -> stringResource(R.string.soft_auth_downloading)
                    else -> Formatter.formatFileSize(LocalContext.current, bytes)
                },
            )
        }
        FilledTonalButton(
            onClick = {
                pickApk.launch(
                    arrayOf(
                        "application/vnd.android.package-archive",
                        "application/zip",
                        "application/octet-stream",
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
            enabled = !busy,
        ) {
            Text(stringResource(R.string.soft_auth_import))
        }
        Field(
            R.string.soft_auth_url,
            url,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (urlFocused && !state.isFocused) viewModel.saveSoftAuthApkUrl(committedUrl())
                    urlFocused = state.isFocused
                },
            keyboard = KeyboardType.Uri,
            onDone = { viewModel.saveSoftAuthApkUrl(committedUrl()) },
        ) { url = it }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            val count = if (developer) 3 else 1
            SwitchItem(R.string.developer_options, R.drawable.code_24px, developer, viewModel::setDeveloper, 0, count)
            if (developer) {
                SegmentedListItem(
                    onClick = onIap2,
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = count),
                    leadingContent = { Icon(painterResource(R.drawable.badge_24px), null) },
                    trailingContent = { Icon(painterResource(R.drawable.chevron_right_24px), null) },
                    supportingContent = { Text(stringResource(R.string.iap2_summary)) },
                ) {
                    Text(stringResource(R.string.iap2_title))
                }
                SwitchItem(R.string.nmea_logging_title, R.drawable.article_24px, nmeaLogging, viewModel::setNmeaLogging, 2, count)
            }
        }
    }
}

@Composable
private fun SwitchItem(
    @StringRes title: Int,
    @DrawableRes icon: Int,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    SegmentedListItem(
        onClick = { onChecked(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = { Icon(painterResource(icon), null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) },
    ) {
        Text(stringResource(title))
    }
}
