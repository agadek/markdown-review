package pl.mdreview.ide

import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.mdreview.api.RenderedDiff
import pl.mdreview.render.Theme
import java.awt.Color
import java.util.UUID

/**
 * Wraps the renderer's fragment into a full page: theme variables from the current editor scheme,
 * a CSP that blocks every script but ours, and the bridge script that talks back to Kotlin.
 */
object HtmlPage {

    /** @param queryJs JS expression from `JBCefJSQuery.inject("msg")`, with `msg` as the payload variable. */
    fun build(diff: RenderedDiff, queryJs: String, restoreIdx: Int): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src file: data:; style-src 'unsafe-inline'; script-src 'nonce-$nonce'">
            <style>${themeCss()}</style>
            <style>${diff.css}</style>
            <style>$PANEL_CSS</style>
            </head><body>
            ${diff.bodyHtml}
            <script nonce="$nonce">
            window.mdrSend = function(msg) { $queryJs };
            $BRIDGE_JS
            mdr.restore($restoreIdx);
            </script>
            </body></html>
        """.trimIndent()
    }

    fun themeCss(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        val dark = ColorUtil.isDark(bg)
        fun diffBg(key: com.intellij.openapi.editor.colors.TextAttributesKey, fallback: Color) =
            scheme.getAttributes(key)?.backgroundColor ?: fallback
        val removed = diffBg(DiffColors.DIFF_DELETED, if (dark) Color(0x484A4A) else Color(0xD6D6D6))
        val added = diffBg(DiffColors.DIFF_INSERTED, if (dark) Color(0x294436) else Color(0xBEE6BE))
        val modified = diffBg(DiffColors.DIFF_MODIFIED, if (dark) Color(0x385570) else Color(0xC2D8F2))
        // Removed blocks are red per spec, regardless of IntelliJ's grey "deleted" color.
        val removedRed = if (dark) Color(0x5C2B2B) else Color(0xF7D4D4)
        val vars = mapOf(
            "--md-bg" to css(bg),
            "--md-fg" to css(fg),
            "--md-muted" to css(UIUtil.getContextHelpForeground()),
            "--md-border" to css(JBColor.border()),
            "--md-link" to css(JBUI.CurrentTheme.Link.Foreground.ENABLED),
            "--md-code-bg" to css(ColorUtil.mix(bg, fg, 0.06)),
            "--md-removed-bg" to css(if (isGrey(removed)) removedRed else removed),
            "--md-added-bg" to css(added),
            "--md-modified-bg" to css(modified),
            "--md-placeholder-bg" to css(ColorUtil.mix(bg, fg, 0.03)),
            "--md-removed-word-bg" to css(ColorUtil.mix(if (isGrey(removed)) removedRed else removed, fg, 0.15)),
            "--md-added-word-bg" to css(ColorUtil.mix(added, fg, 0.15)),
            "--md-font" to "'${UIUtil.getLabelFont().family}', system-ui, sans-serif",
            "--md-font-size" to "${UIUtil.getLabelFont().size}px",
            "--md-mono-font" to "'${scheme.editorFontName}', monospace",
        ) + Theme.alertVariables(dark)
        return ":root { color-scheme: ${if (dark) "dark" else "light"}; " +
            vars.entries.joinToString(" ") { "${it.key}: ${it.value};" } + " }"
    }

    private fun isGrey(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) < 16

    private fun css(c: Color) = "#" + ColorUtil.toHex(c)

    private const val PANEL_CSS = """
        .mdr-current, .mdr-current > [data-side] { outline: 2px solid var(--md-link); outline-offset: -2px; }
        [data-side][data-line] { cursor: text; }
    """

    /**
     * Messages to Kotlin are `action|arg|arg` strings:
     * `scroll|<idx>` topmost visible block, `source|<side>|<line>` Ctrl/Cmd+click on a block,
     * `link|<href>` external link click.
     */
    private const val BRIDGE_JS = """
        var mdr = (function() {
          function block(idx) { return document.querySelector('[data-idx="' + idx + '"]'); }
          function box(el) { return el.getClientRects().length ? el : (el.firstElementChild || el); }
          function reveal(el) {
            for (var p = el.parentElement; p; p = p.parentElement) if (p.tagName === 'DETAILS') p.open = true;
          }
          var scrollTimer = null;
          window.addEventListener('scroll', function() {
            clearTimeout(scrollTimer);
            scrollTimer = setTimeout(function() {
              var blocks = document.querySelectorAll('[data-idx]');
              for (var i = 0; i < blocks.length; i++) {
                var r = box(blocks[i]).getBoundingClientRect();
                if (r.bottom > 0) { mdrSend('scroll|' + blocks[i].getAttribute('data-idx')); return; }
              }
            }, 150);
          });
          document.addEventListener('click', function(e) {
            var a = e.target.closest('a[href]');
            if (a) {
              var href = a.getAttribute('href');
              if (href.charAt(0) === '#') return;
              e.preventDefault();
              mdrSend('link|' + href);
              return;
            }
            if (e.ctrlKey || e.metaKey) {
              var cell = e.target.closest('[data-side][data-line]');
              if (cell) { e.preventDefault(); mdrSend('source|' + cell.getAttribute('data-side') + '|' + cell.getAttribute('data-line')); }
            }
          });
          return {
            goTo: function(idx) {
              var el = block(idx);
              if (!el) return;
              reveal(el);
              var cur = document.querySelectorAll('.mdr-current');
              for (var i = 0; i < cur.length; i++) cur[i].classList.remove('mdr-current');
              el.classList.add('mdr-current');
              box(el).scrollIntoView({block: 'center'});
            },
            restore: function(idx) {
              var el = idx > 0 ? block(idx) : null;
              if (el) { reveal(el); box(el).scrollIntoView({block: 'start'}); }
            }
          };
        })();
    """
}
