package pl.mdreview.render

internal object Styles {

    private const val DIFF_CSS = "/pl/mdreview/css/diff.css"

    private val diffCss: String by lazy {
        Styles::class.java.getResourceAsStream(DIFF_CSS)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Missing resource $DIFF_CSS")
    }

    /**
     * `diff.css` (owned by the stylesheet, see CLASSES.md), then the bridge from the IDE's `--md-*`
     * variables to the stylesheet's `--mdr-*` ones, then layout rules for one-block-per-row rendering.
     */
    fun css(): String = diffCss + "\n" + BRIDGE + "\n" + LAYOUT

    /** The IDE defines `--md-*` from the editor scheme (ENGINE_CONTRACT.md); `diff.css` reads `--mdr-*`. */
    private const val BRIDGE = """
html, body { background: var(--md-bg, var(--mdr-bg)); color: var(--md-fg, var(--mdr-fg)); }
.mdr-root {
  --mdr-bg: var(--md-bg);
  --mdr-fg: var(--md-fg);
  --mdr-muted: var(--md-muted);
  --mdr-border: var(--md-border);
  --mdr-quote-border: var(--md-border);
  --mdr-link: var(--md-link);
  --mdr-code-bg: var(--md-code-bg);
  --mdr-added-bg: var(--md-added-bg);
  --mdr-removed-bg: var(--md-removed-bg);
  --mdr-added-inline: var(--md-added-word-bg);
  --mdr-removed-inline: var(--md-removed-word-bg);
  --mdr-placeholder-bg: repeating-linear-gradient(-45deg, transparent 0 6px, var(--md-placeholder-bg) 6px 7px);
  --mdr-font: var(--md-font);
  --mdr-mono: var(--md-mono-font);
  --mdr-font-size: var(--md-font-size);
}
"""

    private const val LAYOUT = """
/* Every block is its own row: keep rows tight so lists, code and tables read as one piece. */
.mdr-root { padding: 0 var(--mdr-gap) var(--mdr-gap); }
.mdr-sbs .mdr-side > blockquote { margin: 0; height: 100%; }
.mdr-merged .mdr-item > blockquote { margin: 0; }
.mdr-block > :first-child { margin-top: 0.3em; }
.mdr-block > :last-child { margin-bottom: 0.3em; }
.mdr-block.mdr-modified { background: var(--md-modified-bg, transparent); }
.mdr-empty { padding: 0.6em 0; }

ul.mdr-li, ol.mdr-li { margin: 0; padding-left: calc(1.8em + var(--depth, 0) * 1.8em); }
ul.mdr-li > li > p, ol.mdr-li > li > p { margin: 0.15em 0; }
ul.mdr-li > li.mdr-block > :first-child, ol.mdr-li > li.mdr-block > :first-child { margin-top: 0; }
ul.mdr-li > li.mdr-block, ol.mdr-li > li.mdr-block { padding-top: 0.1em; padding-bottom: 0.1em; }

table.mdr-trow { width: 100%; table-layout: fixed; margin: 0; }
table.mdr-trow td { border-top-width: 0; }
/* diff.css marks rows with a first-cell shadow; a ::before on <tr> would become an extra cell. */
tr.mdr-block::before { content: none; display: none; }
.mdr-sbs .mdr-side > table.mdr-trow { height: 100%; }

.mdr-fence { margin-top: 0.4em; padding: 0.1em 0.8em; background: var(--mdr-code-bg); color: var(--mdr-muted);
  font-family: var(--mdr-mono); font-size: 0.75em; border-radius: 4px 4px 0 0; }
.mdr-fence.mdr-block::before, .mdr-codeline.mdr-block::before { top: 0; bottom: 0; }
.mdr-codeline { padding: 0; }
.mdr-codeline > pre { margin: 0 !important; border-radius: 0; padding-top: 0; padding-bottom: 0; }
.mdr-codeline.mdr-added > pre, .mdr-codeline.mdr-removed > pre { background: transparent; }
.mdr-fm-line > pre { color: var(--mdr-muted); }

details.mdr-fold > summary { margin: 2px 0; padding: 2px 0; text-align: center; cursor: pointer; list-style: none;
  color: var(--mdr-muted); background: var(--mdr-code-bg); border-radius: 3px; font-size: 0.85em; user-select: none; }
details.mdr-fold > summary::-webkit-details-marker { display: none; }
details.mdr-fold[open] > summary { opacity: 0.6; }
"""
}
