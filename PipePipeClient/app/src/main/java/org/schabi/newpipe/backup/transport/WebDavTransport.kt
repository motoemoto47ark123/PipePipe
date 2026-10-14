package org.schabi.newpipe.backup.transport

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/** Uploads backups to a WebDAV server, such as Nextcloud, ownCloud or a NAS. */
class WebDavTransport(private val settings: ServerSettings) : BackupTransport {
    private val client: OkHttpClient by lazy { createClient() }
    private val folderSegments = BackupTransport.folderSegments(settings.folder)

    private fun createClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(settings.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(settings.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(settings.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
        if (settings.protocol == Protocol.WEBDAV_HTTPS && settings.allowUntrustedCertificates) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustAll), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    /** The URL of a folder made of the first [depth] backup folder segments, or of a file in it. */
    private fun url(depth: Int = folderSegments.size, file: String? = null): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme(if (settings.protocol == Protocol.WEBDAV_HTTPS) "https" else "http")
            .host(settings.host)
            .port(settings.effectivePort)
        for (segment in folderSegments.take(depth)) {
            builder.addPathSegment(segment)
        }
        // A trailing empty segment makes the URL end with a slash, as WebDAV expects for folders
        builder.addPathSegment(file ?: "")
        return builder.build()
    }

    private fun request(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        if (settings.username.isNotEmpty() || settings.password.isNotEmpty()) {
            builder.header(
                "Authorization",
                Credentials.basic(settings.username, settings.password, Charsets.UTF_8)
            )
        }
        return builder
    }

    private fun execute(request: Request, vararg expected: Int): Response {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        if (response.code in expected || (expected.isEmpty() && response.isSuccessful)) {
            return response
        }
        response.close()
        when (response.code) {
            401, 403 -> throw AuthenticationException(
                "The server rejected the login (HTTP ${response.code})"
            )
            else -> throw IOException(
                "${request.method} ${request.url.encodedPath} failed: HTTP ${response.code} " +
                    response.message
            )
        }
    }

    private fun propfind(url: HttpUrl, depth: Int): Request = request(url)
        .header("Depth", depth.toString())
        .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
        .build()

    override fun connect() {
        // An existing folder answers with 207 Multi-Status, a missing one with 404
        val code = execute(propfind(url(), 0), 207, 404).use { it.code }
        if (code == 207) {
            return
        }
        for (depth in 1..folderSegments.size) {
            // 405 means that the folder already exists
            execute(request(url(depth)).method("MKCOL", null).build(), 201, 405).close()
        }
        execute(propfind(url(), 0), 207).close()
    }

    override fun list(): List<RemoteFile> {
        val body = execute(propfind(url(), 1), 207).use { it.body.byteStream().readBytes() }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // The listing comes from the server, so it must not make the parser load other files
        factory.isExpandEntityReferences = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (ignored: Exception) {
            // Not supported by every parser
        }
        val document = try {
            factory.newDocumentBuilder().parse(body.inputStream())
        } catch (e: Exception) {
            throw IOException("Could not read the folder listing", e)
        }

        val folderPath = url().encodedPath
        val responses = document.getElementsByTagNameNS(DAV, "response")
        val files = ArrayList<RemoteFile>()
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = response.getElementsByTagNameNS(DAV, "href").item(0)?.textContent?.trim()
                ?: continue
            val isFolder = response.getElementsByTagNameNS(DAV, "collection").length > 0
            val path = (url().resolve(href) ?: continue).encodedPath
            if (isFolder || path == folderPath) {
                continue
            }
            val name = url().resolve(href)?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: continue
            val size = response.getElementsByTagNameNS(DAV, "getcontentlength").item(0)
                ?.textContent?.trim()?.toLongOrNull() ?: -1
            files.add(RemoteFile(name, size))
        }
        return files
    }

    override fun upload(source: File, name: String) {
        val partial = name + BackupTransport.PARTIAL_SUFFIX
        val body: RequestBody = source.asRequestBody(ZIP)
        execute(request(url(file = partial)).put(body).build()).close()
        execute(
            request(url(file = partial))
                .method("MOVE", null)
                .header("Destination", url(file = name).toString())
                .header("Overwrite", "T")
                .build()
        ).close()
    }

    override fun download(name: String, target: File) {
        execute(request(url(file = name)).get().build()).use { response ->
            FileOutputStream(target).use { output -> response.body.byteStream().copyTo(output) }
        }
    }

    override fun delete(name: String) {
        execute(request(url(file = name)).delete().build(), 200, 202, 204, 404).close()
    }

    override fun close() {
        // Connections are reused by OkHttp and time out by themselves
    }

    companion object {
        private const val DAV = "DAV:"
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ZIP = "application/zip".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
    }
}
