package pl.mdreview.render

/** Colors for standalone pages (previews, tests). Inside the IDE the editor scheme provides them. */
data class Theme(
    val dark: Boolean,
    val background: String,
    val foreground: String,
    val muted: String,
    val border: String,
    val link: String,
    val codeBackground: String,
    val removedBackground: String,
    val addedBackground: String,
    val modifiedBackground: String,
    val removedWord: String,
    val addedWord: String,
    val fontFamily: String = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif",
    val monoFamily: String = "'JetBrains Mono', Menlo, Consolas, monospace",
    val fontSizePx: Int = 14,
) {
    /** `:root` definitions of the `--md-*` variables the stylesheet reads (the IDE sets its own). */
    fun cssVariables(): String = buildString {
        append(":root { color-scheme: ").append(if (dark) "dark" else "light").append(";")
        val vars = linkedMapOf(
            "--md-bg" to background, "--md-fg" to foreground, "--md-muted" to muted, "--md-border" to border,
            "--md-link" to link, "--md-code-bg" to codeBackground, "--md-removed-bg" to removedBackground,
            "--md-added-bg" to addedBackground, "--md-modified-bg" to modifiedBackground,
            "--md-placeholder-bg" to codeBackground, "--md-removed-word-bg" to removedWord,
            "--md-added-word-bg" to addedWord, "--md-font" to fontFamily, "--md-font-size" to "${fontSizePx}px",
            "--md-mono-font" to monoFamily,
        )
        for ((k, v) in vars) append(' ').append(k).append(": ").append(v).append(';')
        append(" }")
    }

    companion object {
        val LIGHT = Theme(
            dark = false,
            background = "#ffffff",
            foreground = "#1f2328",
            muted = "#656d76",
            border = "#d0d7de",
            link = "#0969da",
            codeBackground = "#f6f8fa",
            removedBackground = "#ffebe9",
            addedBackground = "#e6ffec",
            modifiedBackground = "#eef4ff",
            removedWord = "#ffb3ad",
            addedWord = "#9be9a8",
        )

        val DARK = Theme(
            dark = true,
            background = "#1e1f22",
            foreground = "#dfe1e5",
            muted = "#8c8f94",
            border = "#3c3f45",
            link = "#589df6",
            codeBackground = "#2b2d30",
            removedBackground = "#4a2a2c",
            addedBackground = "#29402f",
            modifiedBackground = "#28344a",
            removedWord = "#7a3b3e",
            addedWord = "#3d6b47",
        )
    }
}
