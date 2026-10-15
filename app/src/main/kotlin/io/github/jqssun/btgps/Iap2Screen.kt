package io.github.jqssun.btgps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jqssun.btgps.iap2.BluetoothTransportComponent
import io.github.jqssun.btgps.iap2.ControlSessionMessage
import io.github.jqssun.btgps.iap2.ExternalAccessoryProtocol
import io.github.jqssun.btgps.iap2.THIRD_PARTY
import io.github.jqssun.btgps.iap2.IdentificationInformation
import io.github.jqssun.btgps.iap2.MatchAction
import io.github.jqssun.btgps.iap2.PowerProvidingCapability
import io.github.jqssun.btgps.iap2.Source
import io.github.jqssun.btgps.iap2.U16
import io.github.jqssun.btgps.iap2.U8

@Composable
fun Iap2Screen(
    savedMac: String,
    identification: IdentificationInformation,
    onSave: (IdentificationInformation, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(identification) { mutableStateOf(identification) }
    var custom by remember(identification) { mutableStateOf(identification.takeIf { Preset.of(it) == Preset.Custom }) }
    val edit = { d: IdentificationInformation ->
        draft = d
        if (Preset.of(d) == Preset.Custom) custom = d
    }
    var mac by rememberSaveable(savedMac) { mutableStateOf(savedMac) }
    val macOk = mac.isBlank() || parseMac(mac) != null
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(R.string.preset_title) {
            ConnectedPicker(Preset.entries, Preset.of(draft), { it.label }) { draft = it.info ?: custom ?: draft }
            Button(
                onClick = { onSave(draft, mac) },
                shapes = ButtonDefaults.shapes(),
                enabled = draft.valid && macOk && (draft != identification || mac != savedMac),
            ) {
                Text(stringResource(R.string.btn_save))
            }
        }
        AccessorySection(draft, edit)
        MessagesSection(draft, edit)
        PowerSection(draft, edit)
        ProtocolsSection(draft, edit)
        LocationSection(draft, edit)
        BluetoothSection(draft, edit)
        MacCard(mac, macOk) { mac = it }
    }
}

private enum class Preset(@StringRes val label: Int, val info: IdentificationInformation?) {
    Default(R.string.preset_default, IdentificationInformation()),
    ThirdParty(R.string.preset_third_party, THIRD_PARTY),
    Custom(R.string.preset_custom, null);

    companion object {
        fun of(info: IdentificationInformation) = entries.firstOrNull { it.info == info } ?: Custom
    }
}

@Composable
private fun MacCard(mac: String, ok: Boolean, onValue: (String) -> Unit) {
    Section(R.string.mac_title) {
        Text(stringResource(R.string.mac_summary), style = MaterialTheme.typography.bodyMedium)
        Field(R.string.mac_label, mac, R.string.error_invalid.takeUnless { ok }, placeholder = R.string.mac_placeholder, onValue = onValue)
    }
}

@Composable
private fun AccessorySection(d: IdentificationInformation, onValue: (IdentificationInformation) -> Unit) {
    Section(R.string.identification_title) {
        Field(R.string.identification_name, d.name, required(d.name)) { onValue(d.copy(name = it)) }
        Field(R.string.identification_model_identifier, d.modelIdentifier, required(d.modelIdentifier)) {
            onValue(d.copy(modelIdentifier = it))
        }
        Field(R.string.identification_manufacturer, d.manufacturer, required(d.manufacturer)) {
            onValue(d.copy(manufacturer = it))
        }
        Field(R.string.identification_serial_number, d.serialNumber, required(d.serialNumber)) {
            onValue(d.copy(serialNumber = it))
        }
        Field(R.string.identification_firmware_version, d.firmwareVersion, required(d.firmwareVersion)) {
            onValue(d.copy(firmwareVersion = it))
        }
        Field(R.string.identification_hardware_version, d.hardwareVersion, required(d.hardwareVersion)) {
            onValue(d.copy(hardwareVersion = it))
        }
        Field(R.string.identification_app_match_team_id, d.appMatchTeamID, hint = R.string.hint_optional) {
            onValue(d.copy(appMatchTeamID = it))
        }
        Field(
            R.string.identification_current_language,
            d.currentLanguage,
            error = R.string.error_unsupported_language.takeIf { d.currentLanguage !in d.supportedLanguage },
        ) { onValue(d.copy(currentLanguage = it)) }
        LanguageList(d.supportedLanguage) { onValue(d.copy(supportedLanguage = it)) }
    }
}

@Composable
private fun MessagesSection(d: IdentificationInformation, onValue: (IdentificationInformation) -> Unit) {
    Section(R.string.identification_messages) {
        MessageList(R.string.identification_messages_sent_by_accessory, Source.Accessory, d.messagesSentByAccessory) {
            onValue(d.copy(messagesSentByAccessory = it))
        }
        MessageList(R.string.identification_messages_received_from_device, Source.Device, d.messagesReceivedFromDevice) {
            onValue(d.copy(messagesReceivedFromDevice = it))
        }
    }
}

@Composable
private fun PowerSection(d: IdentificationInformation, onValue: (IdentificationInformation) -> Unit) {
    Section(R.string.identification_power) {
        Dropdown(
            R.string.identification_power_providing_capability,
            d.powerProvidingCapability,
            PowerProvidingCapability.entries,
            ::label,
        ) { onValue(d.copy(powerProvidingCapability = it)) }
        NumberField(R.string.identification_maximum_current_drawn_from_device, d.maximumCurrentDrawnFromDevice, U16) {
            onValue(d.copy(maximumCurrentDrawnFromDevice = it))
        }
    }
}

@Composable
private fun ProtocolsSection(d: IdentificationInformation, onValue: (IdentificationInformation) -> Unit) {
    val protocols = d.supportedExternalAccessoryProtocol
    val set = { list: List<ExternalAccessoryProtocol> -> onValue(d.copy(supportedExternalAccessoryProtocol = list)) }
    Section(R.string.identification_supported_external_accessory_protocol) {
        protocols.forEachIndexed { i, p ->
            val update = { v: ExternalAccessoryProtocol -> set(protocols.toMutableList().also { it[i] = v }) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberField(
                            R.string.external_accessory_protocol_identifier,
                            p.externalAccessoryProtocolIdentifier,
                            U8,
                            Modifier.weight(1f),
                            R.string.error_duplicate.takeIf { p.externalAccessoryProtocolIdentifier in d.duplicateProtocolIdentifiers },
                        ) { update(p.copy(externalAccessoryProtocolIdentifier = it)) }
                        IconButton(
                            onClick = { set(protocols.filterIndexed { j, _ -> j != i }) },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(painterResource(R.drawable.delete_24px), stringResource(R.string.btn_remove))
                        }
                    }
                    Field(R.string.external_accessory_protocol_name, p.externalAccessoryProtocolName, required(p.externalAccessoryProtocolName)) {
                        update(p.copy(externalAccessoryProtocolName = it))
                    }
                    Dropdown(
                        R.string.external_accessory_protocol_match_action,
                        p.externalAccessoryProtocolMatchAction,
                        MatchAction.entries,
                        ::label,
                    ) { update(p.copy(externalAccessoryProtocolMatchAction = it)) }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val next = (protocols.maxOfOrNull { it.externalAccessoryProtocolIdentifier } ?: -1) + 1
                set(protocols + ExternalAccessoryProtocol(next.coerceIn(U8), "", MatchAction.NoAction))
            },
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(painterResource(R.drawable.add_24px), null, Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.btn_add), Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

@Composable
private fun LocationSection(d: IdentificationInformation, onValue: (IdentificationInformation) -> Unit) {
    Section(R.string.identification_location_information_component) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.location_component_include), Modifier.weight(1f))
            Switch(d.includeLocationComponent, { on ->
                val enableSentences = on && !d.locationFixData && !d.locationTransitData
                onValue(
                    d.copy(
                        includeLocationComponent = on,
                        locationFixData = if (enableSentences) true else d.locationFixData,
                        locationTransitData = if (enableSentences) true else d.locationTransitData,
                    ),
                )
            })
        }
        if (d.includeLocationComponent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.location_fix_data), Modifier.weight(1f))
                Switch(d.locationFixData, { onValue(d.copy(locationFixData = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.location_transit_data), Modifier.weight(1f))
                Switch(d.locationTransitData, { onValue(d.copy(locationTransitData = it)) })
            }
        }
    }
}

@Composable
private fun BluetoothSection(d: IdentificationInformation, onValue: (IdentificationInformation) -> Unit) {
    val bt = d.bluetoothTransportComponent
    val set = { v: BluetoothTransportComponent -> onValue(d.copy(bluetoothTransportComponent = v)) }
    Section(R.string.identification_bluetooth_transport_component) {
        NumberField(R.string.transport_component_identifier, bt.transportComponentIdentifier, U16) {
            set(bt.copy(transportComponentIdentifier = it))
        }
        Field(R.string.transport_component_name, bt.transportComponentName, hint = R.string.hint_transport_component_name) {
            set(bt.copy(transportComponentName = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transport_supports_iap2_connection), Modifier.weight(1f))
            Switch(bt.transportSupportsiAP2Connection, { set(bt.copy(transportSupportsiAP2Connection = it)) })
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(
    @StringRes label: Int,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier.fillMaxWidth(),
    @StringRes error: Int? = null,
    onValue: (Int) -> Unit,
) {
    Field(
        label,
        if (value < 0) "" else value.toString(),
        R.string.error_invalid.takeIf { value !in range } ?: error,
        modifier,
        keyboard = KeyboardType.Number,
    ) { text -> onValue(text.filter(Char::isDigit).take(6).toIntOrNull() ?: -1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(
    @StringRes label: Int,
    value: T,
    options: List<T>,
    text: (T) -> Int,
    onValue: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = stringResource(text(value)),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach {
                DropdownMenuItem(text = { Text(stringResource(text(it))) }, onClick = { onValue(it); expanded = false })
            }
        }
    }
}

@Composable
private fun <T> ChipList(
    @StringRes label: Int,
    items: List<T>,
    text: (T) -> String,
    onValue: (List<T>) -> Unit,
    add: @Composable () -> Unit = {},
) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            InputChip(
                selected = false,
                onClick = { onValue(items - item) },
                label = { Text(text(item)) },
                shapes = InputChipDefaults.shapes(),
                trailingIcon = {
                    Icon(
                        painterResource(R.drawable.close_24px),
                        stringResource(R.string.btn_remove),
                        Modifier.size(InputChipDefaults.IconSize),
                    )
                },
            )
        }
        add()
    }
}

@Composable
private fun MessageList(
    @StringRes label: Int,
    source: Source,
    items: List<ControlSessionMessage>,
    onValue: (List<ControlSessionMessage>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ChipList(label, items, { it.name }, onValue) {
        Box {
            AssistChip(
                onClick = { expanded = true },
                label = { Text(stringResource(R.string.btn_add)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.add_24px), null, Modifier.size(AssistChipDefaults.IconSize))
                },
            )
            DropdownMenu(expanded, { expanded = false }) {
                ControlSessionMessage.entries.filter { it.source == source && it !in items }.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.name) },
                        onClick = { onValue(items + m); expanded = false },
                        trailingIcon = { Text("0x%04X".format(m.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageList(items: List<String>, onValue: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    val add = {
        if (text.isNotBlank() && text.trim() !in items) onValue(items + text.trim())
        text = ""
    }
    ChipList(R.string.identification_supported_language, items, { it }, onValue)
    Field(
        R.string.identification_language_code,
        text,
        R.string.error_required.takeIf { items.isEmpty() },
        trailing = {
            IconButton(onClick = add, shapes = IconButtonDefaults.shapes(), enabled = text.isNotBlank()) {
                Icon(painterResource(R.drawable.add_24px), stringResource(R.string.btn_add))
            }
        },
        onDone = add,
    ) { text = it }
}

private fun required(value: String) = R.string.error_required.takeIf { value.isBlank() }

@StringRes
private fun label(p: PowerProvidingCapability) = when (p) {
    PowerProvidingCapability.None -> R.string.power_none
    PowerProvidingCapability.LightningReceptaclePassthrough -> R.string.power_lightning_receptacle_passthrough
    PowerProvidingCapability.Advanced -> R.string.power_advanced
}

@StringRes
private fun label(a: MatchAction) = when (a) {
    MatchAction.NoAction -> R.string.match_action_no_action
    MatchAction.OptionalAction -> R.string.match_action_optional_action
    MatchAction.NoAlert -> R.string.match_action_no_alert
    MatchAction.NoCommunicationProtocol -> R.string.match_action_no_communication_protocol
}
