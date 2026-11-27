package jp.simplespace.watch

import jp.simplespace.ai.ModerationAttachment
import jp.simplespace.discord.OhagiBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

object UrlImageFetcher {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_REDIRECTS = 2

    private val URL_REGEX = Regex("""https?://[^\s<>()\[\]"']+""", RegexOption.IGNORE_CASE)
    private val IMAGE_EXT_REGEX = Regex("""\.(png|jpe?g|webp|gif)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val FORMAT_QUERY_REGEX = Regex("""[?&](?:format|fm)=(png|jpe?g|webp|gif)(?:&|$)""", RegexOption.IGNORE_CASE)

    private val ALLOWED_IMAGE_MIMES = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/gif",
    )

    internal fun extractAllUrls(text: String): List<String> =
        URL_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '>', '」', '）', '。', '、') }
            .distinct()
            .toList()

    internal fun looksLikeDirectImageUrl(url: String): Boolean {
        if (IMAGE_EXT_REGEX.containsMatchIn(url)) return true
        if (FORMAT_QUERY_REGEX.containsMatchIn(url)) return true
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "pbs.twimg.com" ||
            host == "i.imgur.com" ||
            host == "i.redd.it" ||
            host == "media.discordapp.net" ||
            host.endsWith(".discordapp.net")
    }

    internal fun extractDirectImageUrls(text: String): List<String> =
        extractAllUrls(text).filter { looksLikeDirectImageUrl(it) }

    internal fun isPublicSafeAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return false
        }
        val bytes = addr.address
        if (addr is Inet4Address && bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 0.0.0.0/8, 100.64.0.0/10 (CGNAT), 169.254.0.0/16 (Link-Local/Metadata), 192.0.0.0/24, 198.18.0.0/15
            if (b0 == 0) return false
            if (b0 == 100 && b1 in 64..127) return false
            if (b0 == 169 && b1 == 254) return false
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false
            if (b0 >= 224) return false
        } else if (addr is Inet6Address && bytes.size == 16) {
            val b0 = bytes[0].toInt() and 0xFF
            // Unique Local fc00::/7
            if ((b0 and 0xFE) == 0xFC) return false
        }
        return true
    }

    private fun validateUrlForFetch(rawUrl: String): URI? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return null
        if (addresses.isEmpty() || addresses.any { !isPublicSafeAddress(it) }) {
            OhagiBot.logger.warn("SSRF防止のため非パブリックIPのURL取得をブロックしました: host={}", host)
            return null
        }
        return uri
    }

    suspend fun fetchImage(
        rawUrl: String,
        maxBytes: Long = MessageBatchBuffer.MAX_FILE_BYTES,
    ): ModerationAttachment? = withContext(Dispatchers.IO) {
        var currentUrl = rawUrl
        var hops = 0

        while (hops <= MAX_REDIRECTS) {
            val uri = validateUrlForFetch(currentUrl) ?: return@withContext null
            val conn = (uri.toURL().openConnection() as? HttpURLConnection) ?: return@withContext null
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "OhagiBot/1.0 (Discord Moderation)")
                conn.setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif,*/*;q=0.5")

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")?.trim()
                    if (location.isNullOrEmpty()) return@withContext null
                    currentUrl = uri.resolve(location).toString()
                    hops++
                    continue
                }
                if (code !in 200..299) {
                    return@withContext null
                }

                val contentType = conn.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.let { if (it == "image/jpg") "image/jpeg" else it }
                if (contentType == null || contentType !in ALLOWED_IMAGE_MIMES) {
                    return@withContext null
                }

                val contentLength = conn.contentLengthLong
                if (contentLength > maxBytes) {
                    return@withContext ModerationAttachment(
                        fileName = guessFileName(uri, contentType),
                        mimeType = contentType,
                        sizeBytes = contentLength,
                        skippedReason = "URL画像サイズが上限(8MB)を超過",
                    )
                }

                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) {
                            return@withContext ModerationAttachment(
                                fileName = guessFileName(uri, contentType),
                                mimeType = contentType,
                                sizeBytes = total,
                                skippedReason = "URL画像サイズが上限(8MB)を超過",
                            )
                        }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                if (bytes.isEmpty()) return@withContext null

                return@withContext ModerationAttachment(
                    fileName = guessFileName(uri, contentType),
                    mimeType = contentType,
                    sizeBytes = bytes.size.toLong(),
                    bytes = bytes,
                )
            } catch (e: Exception) {
                OhagiBot.logger.debug("URL画像の取得をスキップしました: url={}, err={}", currentUrl, e.message)
                return@withContext null
            } finally {
                conn.disconnect()
            }
        }
        null
    }

    private fun guessFileName(uri: URI, mimeType: String): String {
        val lastSeg = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it.length <= 80 }
        if (lastSeg != null && lastSeg.contains('.')) return lastSeg
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "img"
        }
        return "url_image.$ext"
    }
}
