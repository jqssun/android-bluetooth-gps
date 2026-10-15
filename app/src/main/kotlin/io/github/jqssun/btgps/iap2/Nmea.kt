package io.github.jqssun.btgps.iap2

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class Fix(
    val time: Instant,
    val lat: Double,
    val lon: Double,
    val quality: Int,
    val sats: Int,
    val hdop: Double,
    val alt: Double,
    val geoid: Double?,
    val knots: Double,
    val course: Double,
    val magVar: Double?, // negative is west
    val mode: Char,
)

data class Sat(val svid: Int, val elev: Int, val az: Int, val snr: Int)

/** alt above MSL */
@Serializable
data class CustomLocation(
    val lat: Double = 51.895,
    val lon: Double = 0.250,
    val alt: Double = 72.000,
) {
    val latOk get() = lat in -90.0..90.0
    val lonOk get() = lon in -180.0..180.0
    val altOk get() = alt.isFinite()
    val valid get() = latOk && lonOk && altOk
}

@Serializable
data class FixDetail(
    val sats: Int = 36,
    val hdop: Double = 0.4,
) {
    val satsOk get() = sats in 1..99
    val hdopOk get() = hdop.isFinite() && hdop > 0
    val valid get() = satsOk && hdopOk
}

private val THIRD_PARTY_SATS = listOf(
    Sat(49, 30, 179, 40), Sat(79, 57, 179, 36), Sat(23, 34, 264, 41), Sat(12, 29, 209, 40),
    Sat(80, 72, 34, 17), Sat(24, 71, 283, 27), Sat(88, 61, 306, 31), Sat(15, 54, 174, 33),
    Sat(87, 49, 241, 30), Sat(22, 36, 55, 20), Sat(13, 32, 136, 27), Sat(17, 30, 74, 28),
    Sat(19, 28, 102, 33), Sat(10, 25, 313, 24), Sat(14, 20, 51, 23),
)

private val FORWARDED = setOf("GGA", "RMC", "GSV")

private val TIME = DateTimeFormatter.ofPattern("HHmmss.SS").withZone(ZoneOffset.UTC)
private val DATE = DateTimeFormatter.ofPattern("ddMMyy").withZone(ZoneOffset.UTC)

fun nmeaChecksum(sentence: String): String {
    val body = sentence.removePrefix("\$").substringBefore("*")
    var sum = 0
    for (ch in body) sum = sum xor ch.code
    return "%02X".format(sum)
}

val String.nmeaType: String get() = substring(3, 6)
val String.nmeaId: String get() = substring(1).substringBefore(',')

private fun finish(body: String): String = "$body*${nmeaChecksum(body)}"

private fun String.fmt(vararg args: Any): String = String.format(Locale.US, this, *args)

/* ddmm.mmmmmm rounded in micro minutes */
private fun coord(deg: Double, width: Int): String {
    val micro = (abs(deg) * 60_000_000).roundToLong()
    val min = micro % 60_000_000
    return "%0${width}d%02d.%06d".fmt(micro / 60_000_000, min / 1_000_000, min % 1_000_000)
}

fun fixSentences(fix: Fix): List<String> {
    val time = TIME.format(fix.time)
    val pos = "${coord(fix.lat, 2)},${if (fix.lat < 0) 'S' else 'N'},${coord(fix.lon, 3)},${if (fix.lon < 0) 'W' else 'E'}"
    val sats = "%02d".fmt(fix.sats)
    val hdop = "%.1f".fmt(fix.hdop)
    val geoid = fix.geoid?.let { "%.1f".fmt(it) }.orEmpty()
    val course = (fix.course * 10).roundToLong().mod(3600) / 10.0
    val mag = fix.magVar?.let { "%05.1f,%s".fmt(abs(it), if (it < 0) "W" else "E") } ?: ","
    val gga = "\$GPGGA,$time,$pos,${fix.quality},$sats,$hdop,${"%.1f".fmt(fix.alt)},M,$geoid,M,,"
    val rmc = "\$GPRMC,$time,A,$pos,${"%06.2f".fmt(fix.knots)},${"%.1f".fmt(course)},${DATE.format(fix.time)},$mag,${fix.mode}"
    return listOf(finish(gga), finish(rmc))
}

fun gsvSentences(sats: List<Sat>): List<String> {
    val pages = sats.chunked(4)
    return pages.mapIndexed { i, page ->
        val fields = page.joinToString("") { ",%02d,%02d,%03d,%02d".fmt(it.svid, it.elev, it.az, it.snr) }
        finish("\$GPGSV,${pages.size},${i + 1},${"%02d".fmt(sats.size)}$fields")
    }
}

fun customSentences(loc: CustomLocation, detail: FixDetail, instant: Instant): List<String> =
    fixSentences(Fix(instant, loc.lat, loc.lon, 2, detail.sats, detail.hdop, loc.alt, null, 0.0, 0.0, null, 'D')) + gsvSentences(THIRD_PARTY_SATS)

/* GGA/RMC retagged to GP since iOS only uses $GP fixes */
fun passthrough(line: String): String? {
    val s = line.trim()
    if (s.length < 7 || s[0] != '$' || s.nmeaType !in FORWARDED) return null
    val talker = if (s.nmeaType == "GSV") s.take(3) else "\$GP"
    return finish(talker + s.substring(3).substringBefore('*'))
}

fun locationInformation(sentences: List<String>): ByteArray {
    val params = sentences.map { sentence ->
        CsmParam(0, sentence.toByteArray(Charsets.US_ASCII) + 0)
    }
    return writeCsm(ControlSessionMessage.LocationInformation.id, params)
}

fun locationMessages(sentences: List<String>): List<ByteArray> {
    val (gsv, fix) = sentences.partition { it.nmeaType == "GSV" }
    return (fix.chunked(2) + gsv.map(::listOf)).map(::locationInformation)
}
