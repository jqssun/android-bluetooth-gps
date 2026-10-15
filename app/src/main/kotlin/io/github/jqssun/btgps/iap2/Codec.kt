package io.github.jqssun.btgps.iap2

import java.nio.ByteBuffer

internal const val LINK_START = 0xFF5A
internal const val CONTROL_SYN = 0x80
internal const val CONTROL_ACK = 0x40
internal const val CONTROL_SESSION_ID = 1

internal val DETECT_MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)

fun checksum(data: ByteArray): Int {
    var sum = 0
    for (b in data) sum = (sum + (b.toInt() and 0xFF)) and 0xFF
    return (-sum) and 0xFF
}

fun linkPacket(
    control: Int,
    seq: Int,
    ack: Int,
    session: Int,
    payload: ByteArray? = null,
): ByteArray {
    val length = if (payload == null) 9 else payload.size + 10
    val header = ByteBuffer.allocate(8)
    header.putShort(LINK_START.toShort())
    header.putShort(length.toShort())
    header.put(control.toByte())
    header.put(seq.toByte())
    header.put(ack.toByte())
    header.put(session.toByte())
    val headerBytes = header.array()
    val out = ByteBuffer.allocate(length)
    out.put(headerBytes)
    out.put(checksum(headerBytes).toByte())
    if (payload != null) {
        out.put(payload)
        out.put(checksum(payload).toByte())
    }
    return out.array()
}

internal fun ByteArray.u16At(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)

internal fun u16List(value: ByteArray): List<Int> = (0 until value.size / 2).map { value.u16At(it * 2) }

internal fun u16Bytes(values: List<Int>): ByteArray =
    ByteBuffer.allocate(values.size * 2).apply { values.forEach { putShort(it.toShort()) } }.array()

fun linkPayload(packet: ByteArray): ByteArray {
    require(packet.size >= 9) { "link packet shorter than header" }
    val length = packet.u16At(2)
    require(length <= packet.size) { "link length $length exceeds buffer ${packet.size}" }
    if (length <= 9) return ByteArray(0)
    return packet.copyOfRange(9, length - 1)
}

data class LinkHeader(val length: Int, val control: Int, val seq: Int, val ack: Int, val session: Int)

fun parseLinkHeader(packet: ByteArray) = LinkHeader(
    length = packet.u16At(2),
    control = packet[4].toInt() and 0xFF,
    seq = packet[5].toInt() and 0xFF,
    ack = packet[6].toInt() and 0xFF,
    session = packet[7].toInt() and 0xFF,
)

class CsmParam(val id: Int, val value: ByteArray)

data class Csm(val id: Int, val params: List<CsmParam>)

fun writeCsm(id: Int, params: List<CsmParam>): ByteArray {
    val payload = groupBody(params)
    val out = ByteBuffer.allocate(6 + payload.size)
    out.putShort(0x4040.toShort())
    out.putShort((6 + payload.size).toShort())
    out.putShort(id.toShort())
    out.put(payload)
    return out.array()
}

fun parseCsm(payload: ByteArray): Csm {
    require(payload.size >= 6) { "CSM shorter than header" }
    require(payload[0] == 0x40.toByte() && payload[1] == 0x40.toByte()) { "CSM start" }
    val length = payload.u16At(2)
    val id = payload.u16At(4)
    val params = mutableListOf<CsmParam>()
    var offset = 6
    val end = minOf(length, payload.size)
    while (offset + 4 <= end) {
        val dataEnd = offset + payload.u16At(offset)
        require(dataEnd <= end) { "CSM param overruns message" }
        params += CsmParam(payload.u16At(offset + 2), payload.copyOfRange(offset + 4, dataEnd))
        offset = dataEnd
    }
    return Csm(id, params)
}

internal fun groupBody(params: List<CsmParam>): ByteArray {
    val body = ByteBuffer.allocate(params.sumOf { 4 + it.value.size })
    for (param in params) {
        body.putShort((param.value.size + 4).toShort())
        body.putShort(param.id.toShort())
        body.put(param.value)
    }
    return body.array()
}
