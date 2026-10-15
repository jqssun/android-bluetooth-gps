package io.github.jqssun.btgps

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jqssun.btgps.iap2.SoftwareAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoftAuthCerts @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir = File(context.filesDir, "iap2-soft-auth")
    private val _auth = MutableStateFlow<SoftwareAuth?>(null)
    val auth: StateFlow<SoftwareAuth?> = _auth.asStateFlow()

    private val loading = CoroutineScope(Dispatchers.IO).launch {
        _auth.compareAndSet(null, runCatching { _parse(File(dir, CERTIFICATE).readBytes(), File(dir, IDENTITY).readBytes()) }.getOrNull())
    }

    suspend fun load(): SoftwareAuth? {
        loading.join()
        return _auth.value
    }

    fun import(uri: Uri) {
        val stream = context.contentResolver.openInputStream(uri) ?: _fail(R.string.soft_auth_error_open)
        stream.use(::_import)
    }

    fun downloadAndImport(url: String, onProgress: (Long) -> Unit) {
        val tmp = File.createTempFile("soft-auth", ".apk", context.cacheDir)
        try {
            tmp.outputStream().use { _download(url, it, onProgress) }
            tmp.inputStream().use(::_import)
        } finally {
            tmp.delete()
        }
    }

    private fun _download(url: String, output: OutputStream, onProgress: (Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) _fail(R.string.soft_auth_error_http, conn.responseCode)
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                var downloaded = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun _import(input: InputStream) {
        val found = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val name = zip.nextEntry?.name?.substringAfterLast('/') ?: break
                if (name in FILES && name !in found) found[name] = zip.readBytes()
            }
        }
        val certificate = found[CERTIFICATE] ?: _fail(R.string.soft_auth_error_missing, CERTIFICATE)
        val identity = found[IDENTITY] ?: _fail(R.string.soft_auth_error_missing, IDENTITY)
        val auth = _parse(certificate, identity)
        dir.mkdirs()
        _write(CERTIFICATE, certificate)
        _write(IDENTITY, identity)
        _auth.value = auth
    }

    private fun _parse(certificate: ByteArray, identity: ByteArray): SoftwareAuth {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(identity))
        val cert = CertificateFactory.getInstance("X.509").generateCertificates(certificate.inputStream())
            .singleOrNull() as? X509Certificate ?: _fail(R.string.soft_auth_error_certificate)
        val serial = cert.serialNumber.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val auth = SoftwareAuth(certificate, serial, key)
        if (!auth.matches(cert.publicKey)) _fail(R.string.soft_auth_error_mismatch)
        return auth
    }

    private fun _write(name: String, bytes: ByteArray) {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun _fail(@StringRes res: Int, vararg args: Any): Nothing = error(context.getString(res, *args))

    private companion object {
        const val CERTIFICATE = "certificate.p7b"
        const val IDENTITY = "identity.pk8"
        val FILES = setOf(CERTIFICATE, IDENTITY)
    }
}
