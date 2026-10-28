package pl.mdreview.ide

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * The custom stylesheet from Settings | Tools | Markdown Review (P3.6): a CSS file the page applies after its own
 * styles, so it can override the `--md-*` theme variables and any rule of `diff.css`. The `<html>` element carries
 * `mdr-theme-light` or `mdr-theme-dark`, so one file can style both themes.
 *
 * Read on every page build and cached by modification time and size, so edits to the file show on the next render
 * without a re-read of an unchanged file. A missing, unreadable or oversized file is ignored.
 */
object UserStylesheet {

    /** Larger files are ignored: a stylesheet this big is almost certainly the wrong file. */
    const val MAX_BYTES = 1024 * 1024

    private data class Cached(val path: Path, val modified: FileTime, val size: Long, val css: String)

    @Volatile
    private var cached: Cached? = null

    /** The stylesheet at [path], ready to put inside `<style>`; `null` when [path] is blank or cannot be read. */
    fun load(path: String): String? {
        if (path.isBlank()) return null
        val file = try {
            Path.of(path.trim())
        } catch (_: InvalidPathException) {
            return null
        }
        return try {
            if (!Files.isRegularFile(file)) return null
            val modified = Files.getLastModifiedTime(file)
            val size = Files.size(file)
            if (size > MAX_BYTES) return null
            cached?.takeIf { it.path == file && it.modified == modified && it.size == size }?.let { return it.css }
            val css = inline(Files.readString(file))
            cached = Cached(file, modified, size, css)
            css
        } catch (_: Exception) {
            null
        }
    }

    /** [css] made safe inside a `<style>` element: nothing in it can close the element early. */
    fun inline(css: String): String = css.replace("</", "<\\/")
}
