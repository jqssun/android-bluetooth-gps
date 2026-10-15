package io.github.jqssun.btgps.iap2

import io.github.jqssun.btgps.iap2.ControlSessionMessage.AccessoryAuthenticationSerialNumber
import io.github.jqssun.btgps.iap2.ControlSessionMessage.AuthenticationCertificate
import io.github.jqssun.btgps.iap2.ControlSessionMessage.AuthenticationFailed
import io.github.jqssun.btgps.iap2.ControlSessionMessage.AuthenticationResponse
import io.github.jqssun.btgps.iap2.ControlSessionMessage.AuthenticationSucceeded
import io.github.jqssun.btgps.iap2.ControlSessionMessage.LocationInformation
import io.github.jqssun.btgps.iap2.ControlSessionMessage.RequestAppLaunch
import io.github.jqssun.btgps.iap2.ControlSessionMessage.RequestAuthenticationCertificate
import io.github.jqssun.btgps.iap2.ControlSessionMessage.RequestAuthenticationChallengeResponse
import io.github.jqssun.btgps.iap2.ControlSessionMessage.StartExternalAccessoryProtocolSession
import io.github.jqssun.btgps.iap2.ControlSessionMessage.StartLocationInformation
import io.github.jqssun.btgps.iap2.ControlSessionMessage.StopExternalAccessoryProtocolSession
import io.github.jqssun.btgps.iap2.ControlSessionMessage.StopLocationInformation
import kotlinx.serialization.Serializable

enum class PowerProvidingCapability { None, LightningReceptaclePassthrough, Advanced }

enum class MatchAction { NoAction, OptionalAction, NoAlert, NoCommunicationProtocol }

@Serializable
data class ExternalAccessoryProtocol(
    val externalAccessoryProtocolIdentifier: Int,
    val externalAccessoryProtocolName: String,
    val externalAccessoryProtocolMatchAction: MatchAction,
)

@Serializable
data class BluetoothTransportComponent(
    val transportComponentIdentifier: Int = 9,
    val transportComponentName: String = "Bluetooth GPS",
    val transportSupportsiAP2Connection: Boolean = true,
)

@Serializable
data class IdentificationInformation(
    val name: String = "Bluetooth GPS",
    val modelIdentifier: String = "Bluetooth GPS",
    val manufacturer: String = "Bluetooth GPS",
    val serialNumber: String = "000000000000",
    val firmwareVersion: String = "1.0",
    val hardwareVersion: String = "1.0",
    val messagesSentByAccessory: List<ControlSessionMessage> = listOf(
        AuthenticationCertificate,
        AuthenticationResponse,
        AccessoryAuthenticationSerialNumber,
        RequestAppLaunch,
        LocationInformation,
    ),
    val messagesReceivedFromDevice: List<ControlSessionMessage> = listOf(
        RequestAuthenticationCertificate,
        RequestAuthenticationChallengeResponse,
        AuthenticationFailed,
        AuthenticationSucceeded,
        StartLocationInformation,
        StopLocationInformation,
    ),
    val powerProvidingCapability: PowerProvidingCapability = PowerProvidingCapability.None,
    val maximumCurrentDrawnFromDevice: Int = 0,
    val supportedExternalAccessoryProtocol: List<ExternalAccessoryProtocol> = emptyList(),
    val appMatchTeamID: String = "", // optional, blank omits it
    val currentLanguage: String = "en",
    val supportedLanguage: List<String> = listOf("en"),
    val bluetoothTransportComponent: BluetoothTransportComponent = BluetoothTransportComponent(),
    val includeLocationComponent: Boolean = true,
    val locationFixData: Boolean = true,
    val locationTransitData: Boolean = true,
) {
    val duplicateProtocolIdentifiers get() = supportedExternalAccessoryProtocol
        .groupBy { it.externalAccessoryProtocolIdentifier }.filterValues { it.size > 1 }.keys

    val valid get() = listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion)
        .none { it.isBlank() } &&
        currentLanguage in supportedLanguage &&
        duplicateProtocolIdentifiers.isEmpty() &&
        maximumCurrentDrawnFromDevice in U16 &&
        bluetoothTransportComponent.transportComponentIdentifier in U16 &&
        supportedExternalAccessoryProtocol.all { it.externalAccessoryProtocolIdentifier in U8 && it.externalAccessoryProtocolName.isNotBlank() }

    fun encode(bluetoothAddress: ByteArray): ByteArray {
        require(bluetoothAddress.size == 6) { "Bluetooth address must be 6 bytes" }
        val bt = bluetoothTransportComponent
        val params = buildList {
            add(strParam(0, name))
            add(strParam(1, modelIdentifier))
            add(strParam(2, manufacturer))
            add(strParam(3, serialNumber))
            add(strParam(4, firmwareVersion))
            add(strParam(5, hardwareVersion))
            add(messagesParam(6, messagesSentByAccessory))
            add(messagesParam(7, messagesReceivedFromDevice))
            add(u8(8, powerProvidingCapability.ordinal))
            add(u16(9, maximumCurrentDrawnFromDevice))
            supportedExternalAccessoryProtocol.forEach {
                val group = listOf(
                    u8(0, it.externalAccessoryProtocolIdentifier),
                    strParam(1, it.externalAccessoryProtocolName),
                    u8(2, it.externalAccessoryProtocolMatchAction.ordinal),
                )
                add(CsmParam(10, groupBody(group)))
            }
            if (appMatchTeamID.isNotBlank()) add(strParam(11, appMatchTeamID))
            add(strParam(12, currentLanguage))
            supportedLanguage.forEach { add(strParam(13, it)) }
            val component = listOfNotNull(
                u16(0, bt.transportComponentIdentifier),
                strParam(1, bt.transportComponentName),
                CsmParam(2, ByteArray(0)).takeIf { bt.transportSupportsiAP2Connection },
                CsmParam(3, bluetoothAddress),
            )
            add(CsmParam(17, groupBody(component)))
            if (includeLocationComponent) {
                add(
                    CsmParam(
                        22,
                        groupBody(
                            buildList {
                                add(u16(0, 0))
                                add(strParam(1, name))
                                if (locationFixData) add(CsmParam(17, ByteArray(0)))
                                if (locationTransitData) add(CsmParam(18, ByteArray(0)))
                            },
                        ),
                    ),
                )
            }
        }
        return writeCsm(ControlSessionMessage.IdentificationInformation.id, params)
    }
}

val U8 = 0..0xFF
val U16 = 0..0xFFFF

val THIRD_PARTY = IdentificationInformation(
    name = "GLO",
    modelIdentifier = "GLO",
    manufacturer = "Garmin",
    firmwareVersion = "3.00",
    hardwareVersion = "1.00",
    messagesSentByAccessory = listOf(RequestAppLaunch, LocationInformation),
    messagesReceivedFromDevice = listOf(
        StartExternalAccessoryProtocolSession,
        StopExternalAccessoryProtocolSession,
        StartLocationInformation,
        StopLocationInformation,
    ),
    supportedExternalAccessoryProtocol = listOf(
        ExternalAccessoryProtocol(0, "com.garmin.connext", MatchAction.OptionalAction),
        ExternalAccessoryProtocol(1, "com.garmin.pvt", MatchAction.NoAction),
    ),
    appMatchTeamID = "72ES32VZUA",
    bluetoothTransportComponent = BluetoothTransportComponent(transportComponentName = "Garmin GLO"),
    includeLocationComponent = false,
    locationFixData = false,
    locationTransitData = false,
)

private fun strParam(id: Int, value: String) = CsmParam(id, value.toByteArray(Charsets.UTF_8) + 0)
private fun u8(id: Int, value: Int) = CsmParam(id, byteArrayOf(value.toByte()))
private fun u16(id: Int, value: Int) = CsmParam(id, byteArrayOf((value shr 8).toByte(), value.toByte()))
private fun messagesParam(id: Int, messages: List<ControlSessionMessage>) = CsmParam(id, u16Bytes(messages.map { it.id }))
