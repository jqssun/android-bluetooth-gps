package io.github.jqssun.btgps

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jqssun.btgps.ui.theme.BluetoothGPSTheme

private enum class Destination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Session(R.string.session_title, R.drawable.location_on_24px, R.drawable.location_on_fill1_24px),
    Logs(R.string.tab_logs, R.drawable.article_24px, R.drawable.article_fill1_24px),
    Settings(R.string.settings_title, R.drawable.settings_24px, R.drawable.settings_fill1_24px),
}

private enum class SettingsPage { Main, Iap2 }

private val SettingsStackSaver = listSaver<SnapshotStateList<SettingsPage>, SettingsPage>({ it.toList() }, { it.toMutableStateList() })

@Composable
fun BluetoothGPSApp(
    onStart: () -> Unit,
    onStop: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    var dest by rememberSaveable { mutableStateOf(Destination.Session) }
    val settings = rememberSaveable(saver = SettingsStackSaver) { mutableStateListOf(SettingsPage.Main) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedMac by viewModel.savedMac.collectAsStateWithLifecycle()
    val identification by viewModel.identification.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val custom by viewModel.customLocation.collectAsStateWithLifecycle()
    val detail by viewModel.fixDetail.collectAsStateWithLifecycle()
    val certsImported by viewModel.softAuthImported.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.softAuthResult.collect { result ->
            val text = when (result) {
                SoftAuthResult.Imported -> context.getString(R.string.soft_auth_import_success)
                is SoftAuthResult.Failed -> context.getString(R.string.soft_auth_import_failed, result.reason)
            }
            Toast.makeText(context, text, if (result is SoftAuthResult.Failed) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    BluetoothGPSTheme {
        NavigationSuiteScaffold(
            navigationItems = {
                Destination.entries.forEach {
                    val selected = it == dest
                    NavigationSuiteItem(
                        selected = selected,
                        onClick = { dest = it },
                        icon = { Icon(painterResource(if (selected) it.selectedIcon else it.icon), null) },
                        label = { Text(stringResource(it.label)) },
                    )
                }
            },
        ) {
            when (dest) {
                Destination.Session -> Page(dest.label) {
                    SessionScreen(
                        state = state,
                        source = source,
                        feed = feed,
                        onSource = viewModel::setSource,
                        custom = custom,
                        detail = detail,
                        onSaveLocation = viewModel::saveLocation,
                        onStart = {
                            if (certsImported) {
                                onStart()
                            } else {
                                settings.removeRange(1, settings.size)
                                dest = Destination.Settings
                            }
                        },
                        onStop = onStop,
                        modifier = it,
                    )
                }
                Destination.Logs -> Page(dest.label, actions = { LogsActions(viewModel) }) { LogsScreen(viewModel, it) }
                Destination.Settings -> PageStack(settings) { page ->
                    when (page) {
                        SettingsPage.Main -> Page(R.string.settings_title) {
                            SettingsScreen(
                                onIap2 = { if (settings.last() == SettingsPage.Main) settings += SettingsPage.Iap2 },
                                viewModel = viewModel,
                                modifier = it,
                            )
                        }
                        SettingsPage.Iap2 -> Page(R.string.iap2_title, onBack = { settings.removeLastOrNull() }) {
                            Iap2Screen(savedMac, identification, viewModel::saveIap2, it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(
    @StringRes title: Int,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.btn_back))
                        }
                    }
                },
                actions = actions,
            )
        },
    ) { content(Modifier.padding(it)) }
}
