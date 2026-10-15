package io.github.jqssun.btgps.iap2

import io.github.jqssun.btgps.iap2.ControlSessionMessage.*

enum class SessionPhase {
    DETECT,
    NEGOTIATE,
    AUTHENTICATING,
    IDENTIFYING,
    IDENTIFIED,
    STREAMING,
    FAILED,
}

sealed interface SessionFailure {
    data object Authentication : SessionFailure
    data class Identification(val params: List<Int>) : SessionFailure
}

class AccessorySession(
    private val auth: SoftwareAuth,
    identificationInformation: ByteArray,
    private val reportAuthenticationSerial: Boolean = true,
) {
    @Volatile
    var phase: SessionPhase = SessionPhase.DETECT
        private set

    @Volatile
    var failure: SessionFailure? = null
        private set

    @Volatile
    var requested: Set<String> = emptySet()
        private set

    private var identificationPayload = identificationInformation
    private var retriedIdentification = false

    val streaming: Boolean get() = phase == SessionPhase.STREAMING

    private var buffer = ByteArray(0)
    private val unacked = ArrayDeque<Pending>()
    private var sentPsn = 1
    private var lastReceived = 0
    private var handshakeAt = 0L

    @Synchronized
    fun receive(bytes: ByteArray): List<ByteArray> {
        buffer += bytes
        val out = mutableListOf<ByteArray>()
        if (phase == SessionPhase.DETECT) {
            val markerAt = _indexOf(DETECT_MARKER)
            if (markerAt < 0) {
                _drop(buffer.size - (DETECT_MARKER.size - 1))
                return out
            }
            _drop(markerAt + DETECT_MARKER.size)
            phase = SessionPhase.NEGOTIATE
            handshakeAt = System.currentTimeMillis()
            out += _syn()
        }
        while (true) {
            val packet = _nextPacket() ?: break
            out += handle(packet)
        }
        return out
    }

    @Synchronized
    fun tick(nowMs: Long): List<ByteArray> = _handshake(nowMs) + _retransmit(nowMs)

    @Synchronized
    fun gpsPackets(sentences: List<String>): List<ByteArray> {
        val messages = locationMessages(sentences)
        if (!streaming || unacked.size + messages.size > MAX_OUTSTANDING) return emptyList()
        return messages.map(::data)
    }

    private fun _handshake(nowMs: Long): List<ByteArray> {
        val retry = when (phase) {
            SessionPhase.DETECT -> DETECT_RETRY_MS
            SessionPhase.NEGOTIATE -> SYN_RETRY_MS
            else -> return emptyList()
        }
        if (nowMs - handshakeAt < retry) return emptyList()
        handshakeAt = nowMs
        return listOf(if (phase == SessionPhase.DETECT) DETECT_MARKER.copyOf() else _syn())
    }

    private fun _retransmit(nowMs: Long): List<ByteArray> {
        val due = unacked.filter { nowMs - it.sentAtMs >= RETRANSMIT_MS && it.attempts < MAX_RETRANSMITS }
        due.forEach { it.attempts += 1; it.sentAtMs = nowMs }
        if (unacked.any { it.attempts >= MAX_RETRANSMITS }) phase = SessionPhase.FAILED
        return due.map { it.packet }
    }

    private fun _syn(): ByteArray {
        val payload = byteArrayOf(LINK_VERSION.toByte(), MAX_OUTSTANDING.toByte()) +
            u16Bytes(listOf(MAX_PACKET_LENGTH, RETRANSMIT_MS, CUMULATIVE_ACK_MS)) +
            byteArrayOf(
                MAX_RETRANSMITS.toByte(),
                MAX_CUMULATIVE_ACKS.toByte(),
                CONTROL_SESSION_ID.toByte(), CONTROL_SESSION_TYPE.toByte(), SESSION_VERSION.toByte(),
                EA_SESSION_ID.toByte(), EA_SESSION_TYPE.toByte(), SESSION_VERSION.toByte(),
            )
        return linkPacket(CONTROL_SYN, sentPsn, ack = 0, session = 0, payload = payload)
    }

    private fun handle(packet: ByteArray): List<ByteArray> {
        if (packet.size < 9) return emptyList()
        val header = parseLinkHeader(packet)
        if ((header.control and CONTROL_ACK) != 0) acknowledge(header.ack)
        if ((header.control and CONTROL_SYN) != 0) {
            lastReceived = header.seq
            if ((header.control and CONTROL_ACK) != 0 && phase == SessionPhase.NEGOTIATE) {
                phase = SessionPhase.AUTHENTICATING
            }
            return listOf(ack())
        }
        if (header.length <= 9) return emptyList()
        lastReceived = header.seq
        // TODO: EA sessions are not implemented
        if (header.session != CONTROL_SESSION_ID) return listOf(ack())
        val csm = parseCsm(linkPayload(packet))
        return when (ControlSessionMessage.of(csm.id)) {
            RequestAuthenticationCertificate -> {
                phase = SessionPhase.AUTHENTICATING
                if (reportAuthenticationSerial && csm.params.any { it.id == 0 }) {
                    listOf(data(writeCsm(AccessoryAuthenticationSerialNumber.id, listOf(CsmParam(0, auth.serial)))))
                } else {
                    listOf(data(writeCsm(AuthenticationCertificate.id, listOf(CsmParam(0, auth.certificate)))))
                }
            }
            RequestAuthenticationChallengeResponse -> {
                val challenge = csm.params.first { it.id == 0 }.value
                listOf(data(writeCsm(AuthenticationResponse.id, listOf(CsmParam(0, auth.sign(challenge))))))
            }
            AuthenticationFailed -> {
                phase = SessionPhase.FAILED
                failure = SessionFailure.Authentication
                listOf(ack())
            }
            AuthenticationSucceeded -> listOf(ack())
            StartIdentification -> {
                phase = SessionPhase.IDENTIFYING
                listOf(data(identificationPayload))
            }
            IdentificationAccepted -> {
                phase = SessionPhase.IDENTIFIED
                listOf(ack())
            }
            IdentificationRejected -> {
                val corrected = correctedIdentification(csm)
                if (corrected != null) {
                    phase = SessionPhase.IDENTIFYING
                    listOf(data(corrected))
                } else {
                    phase = SessionPhase.FAILED
                    failure = SessionFailure.Identification(csm.params.map { it.id })
                    listOf(ack())
                }
            }
            StartLocationInformation -> {
                requested = csm.params.mapNotNull { LOCATION_TYPES[it.id] }.toSet()
                phase = SessionPhase.STREAMING
                listOf(ack())
            }
            StopLocationInformation -> {
                phase = SessionPhase.IDENTIFIED
                listOf(ack())
            }
            else -> listOf(ack())
        }
    }

    private fun correctedIdentification(rejection: Csm): ByteArray? {
        if (retriedIdentification) return null
        val dropSent = _messageIds(rejection, 6)
        val dropReceived = _messageIds(rejection, 7)
        val dropLocation = rejection.params.any { it.id == 22 }
        if (dropSent.isEmpty() && dropReceived.isEmpty() && !dropLocation) return null
        val current = parseCsm(identificationPayload)
        val next = current.params.mapNotNull { param ->
            when {
                param.id == 22 && dropLocation -> null
                param.id == 6 -> CsmParam(6, u16Bytes(u16List(param.value) - dropSent))
                param.id == 7 -> CsmParam(7, u16Bytes(u16List(param.value) - dropReceived))
                else -> param
            }
        }
        val encoded = writeCsm(current.id, next)
        if (encoded.contentEquals(identificationPayload)) return null
        retriedIdentification = true
        identificationPayload = encoded
        return encoded
    }

    private fun _messageIds(message: Csm, paramId: Int): Set<Int> =
        message.params.firstOrNull { it.id == paramId }?.value?.let(::u16List).orEmpty().toSet()

    private fun data(payload: ByteArray): ByteArray {
        sentPsn = (sentPsn + 1) and 0xFF
        val packet = linkPacket(CONTROL_ACK, sentPsn, lastReceived, CONTROL_SESSION_ID, payload)
        unacked.addLast(Pending(sentPsn, packet, System.currentTimeMillis()))
        return packet
    }

    private fun acknowledge(ack: Int) {
        while (unacked.isNotEmpty() && (ack - unacked.first().seq).mod(256) < MAX_OUTSTANDING) unacked.removeFirst()
    }

    private fun ack(): ByteArray =
        linkPacket(CONTROL_ACK, sentPsn, lastReceived, session = 0, payload = null)

    private fun _nextPacket(): ByteArray? {
        var start = 0
        while (start + 1 < buffer.size && buffer.u16At(start) != LINK_START) start++
        _drop(start)
        if (buffer.size < 9) return null
        val length = buffer.u16At(2)
        if (length < 9 || buffer.size < length) return null
        return buffer.copyOf(length).also { _drop(length) }
    }

    private fun _indexOf(needle: ByteArray): Int =
        (0..buffer.size - needle.size).firstOrNull { i -> needle.indices.all { buffer[i + it] == needle[it] } } ?: -1

    private fun _drop(count: Int) {
        buffer = buffer.copyOfRange(count.coerceIn(0, buffer.size), buffer.size)
    }

    private class Pending(val seq: Int, val packet: ByteArray, var sentAtMs: Long, var attempts: Int = 0)

    private companion object {
        val LOCATION_TYPES = mapOf(
            1 to "GPGGA",
            2 to "GPRMC",
            3 to "GPGSV",
            4 to "PASCD",
            5 to "PAGCD",
            6 to "PAACD",
            7 to "GPHDT",
        )
        const val DETECT_RETRY_MS = 1000
        const val SYN_RETRY_MS = 500
        const val LINK_VERSION = 1
        const val MAX_OUTSTANDING = 32
        const val MAX_PACKET_LENGTH = 1024
        const val RETRANSMIT_MS = 10_000
        const val CUMULATIVE_ACK_MS = 100
        const val MAX_RETRANSMITS = 5
        const val MAX_CUMULATIVE_ACKS = 1
        const val CONTROL_SESSION_TYPE = 0
        const val EA_SESSION_ID = 3
        const val EA_SESSION_TYPE = 2
        const val SESSION_VERSION = 1
    }
}
