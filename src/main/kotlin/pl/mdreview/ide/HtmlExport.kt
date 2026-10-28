package pl.mdreview.ide

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Turns a snapshot of the rendered page (P3.6) into a standalone HTML file to attach to a review or a ticket: the page
 * as drawn, with Mermaid diagrams and formulas already rendered, no scripts, a policy that allows none, and local
 * images inlined as data: URIs so the file opens offline on another machine.
 *
 * The snapshot itself comes from the page's `mdr.snapshot()`, which drops the scripts, block buttons and the open
 * block editor before serializing the document.
 */
object HtmlExport {

    /** Local images above this size stay as `file:` links instead of growing the exported file. */
    const val MAX_INLINED_IMAGE_BYTES = 10L * 1024 * 1024

    /**
     * @param snapshot the serialized page, from `<!DOCTYPE html>` on.
     * @param title the exported document's title.
     * @param remoteImages keep `http:` / `https:` images allowed, as the IDE's page did (NFR-8).
     * @param readImage the bytes of a local image, `null` when it cannot be inlined.
     */
    fun finish(
        snapshot: String,
        title: String,
        remoteImages: Boolean = false,
        readImage: (Path) -> ByteArray? = ::readLocalImage,
    ): String {
        val policy = "default-src 'none'; img-src data:${if (remoteImages) " https: http:" else ""}; " +
            "style-src 'unsafe-inline'; font-src data:"
        var html = CSP.replace(snapshot) { "<meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">" }
        html = SCRIPT.replace(html, "")
        html = FILE_IMAGE.replace(html) { match ->
            val uri = match.groupValues[2].replace("&amp;", "&")
            dataUri(uri, readImage)?.let { "${match.groupValues[1]}\"$it\"" } ?: match.value
        }
        val head = "<title>${escape(title)}</title>"
        html = if (TITLE.containsMatchIn(html)) TITLE.replace(html, Regex.escapeReplacement(head))
        else CHARSET.replaceFirst(html, "$0" + Regex.escapeReplacement(head))
        return html
    }

    /** `name.diff.html` for a compared file `name.md`, or a generic name when there is no file. */
    fun defaultFileName(fileName: String?, extension: String): String {
        val base = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "markdown"
        return "$base.diff.$extension"
    }

    private fun dataUri(uri: String, readImage: (Path) -> ByteArray?): String? {
        val path = try {
            // Without the query or fragment a relative image may carry, which name no file.
            Path.of(URI("file", null, URI(uri).path, null))
        } catch (_: Exception) {
            return null
        }
        val type = IMAGE_TYPES[path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()] ?: return null
        val bytes = readImage(path) ?: return null
        return "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun readLocalImage(path: Path): ByteArray? = try {
        if (Files.isRegularFile(path) && Files.size(path) <= MAX_INLINED_IMAGE_BYTES) Files.readAllBytes(path) else null
    } catch (_: Exception) {
        null
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val CSP = Regex("<meta http-equiv=\"Content-Security-Policy\"[^>]*>", RegexOption.IGNORE_CASE)

    /** Left over only if the page's own snapshot missed one; an exported file runs no scripts. */
    private val SCRIPT = Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val FILE_IMAGE = Regex("(<img\\b[^>]*?\\ssrc=)\"(file:[^\"]+)\"", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("<title>.*?</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CHARSET = Regex("<meta charset=\"utf-8\">", RegexOption.IGNORE_CASE)

    private val IMAGE_TYPES = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "avif" to "image/avif",
    )
}
