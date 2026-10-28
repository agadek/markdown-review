package pl.mdreview.ide

import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.mdreview.api.RenderedDiff
import pl.mdreview.render.Theme
import java.awt.Color
import java.util.Base64
import java.util.UUID

/**
 * Wraps the renderer's fragment into a full page: theme variables from the current editor scheme,
 * a CSP that blocks every script but ours, and the bridge script that talks back to Kotlin.
 */
object HtmlPage {

    /**
     * @param queryJs JS expression from `JBCefJSQuery.inject("msg")`, with `msg` as the payload variable.
     * @param remoteImages also allow `http:` / `https:` images (NFR-8, off by default).
     * @param actions block buttons to add to every block with an edit region (FR-12).
     * @param renderMath draw formulas with the bundled KaTeX (P2.8); off, they stay monospace source.
     */
    fun build(
        diff: RenderedDiff,
        queryJs: String,
        restoreIdx: Int,
        remoteImages: Boolean = false,
        actions: BlockActions = BlockActions.NONE,
        renderMath: Boolean = true,
    ): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val math = renderMath && diff.bodyHtml.contains(MATH_CLASS)
        // KaTeX's fonts are inlined as data: URIs, the only fonts the page may load.
        val fonts = if (math) " font-src data:;" else ""
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${imgSources(remoteImages)}; style-src 'unsafe-inline';$fonts script-src 'nonce-$nonce'">
            <style>${themeCss()}</style>
            <style>${diff.css}</style>
            <style>$PANEL_CSS</style>
            </head><body>
            ${diff.bodyHtml}
            <script nonce="$nonce">
            window.mdrSend = function(msg) { $queryJs };
            $BRIDGE_JS
            mdr.restore($restoreIdx);
            ${actionsJs(diff, actions)}
            </script>
            </body></html>
        """.trimIndent().let { page ->
            // Appended after trimIndent: the library is large and has no indentation to trim.
            val scripts = mermaidScripts(diff, nonce, restoreIdx) + if (math) mathScripts(nonce, restoreIdx) else ""
            if (scripts.isEmpty()) page else page.removeSuffix("</body></html>") + scripts + "\n</body></html>"
        }
    }

    /**
     * KaTeX's stylesheet with its fonts inlined, the bundled `katex.min.js` and the script that draws every
     * `.mdr-math` element, for pages that have one. The scripts run under the page's nonce.
     */
    private fun mathScripts(nonce: String, restoreIdx: Int): String =
        "<style>" + katexCss + "</style>\n" +
            "<script nonce=\"$nonce\">" + katexJs + "</script>\n" +
            "<script nonce=\"$nonce\">" + MATH_RUN_JS + "mdrDrawMath($restoreIdx);</script>"

    private fun resource(path: String): ByteArray =
        HtmlPage::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: error("Missing resource $path")

    /** `katex.min.js`, pinned in `src/main/resources/pl/mdreview/katex` (see THIRD_PARTY_NOTICES.md). */
    private val katexJs: String by lazy { resource("$KATEX_DIR/katex.min.js").toString(Charsets.UTF_8).replace("</script", "<\\/script") }

    /**
     * `katex.min.css` with each font's `src` replaced by its bundled WOFF2 file as a data: URI. A page loaded with
     * `loadHTML` has no base URL for the stylesheet's relative font paths, and the CSP allows no other source.
     */
    private val katexCss: String by lazy {
        val css = resource("$KATEX_DIR/katex.min.css").toString(Charsets.UTF_8)
        KATEX_FONT_SRC.replace(css) { match ->
            val font = Base64.getEncoder().encodeToString(resource("$KATEX_DIR/fonts/${match.groupValues[1]}.woff2"))
            "src:url(data:font/woff2;base64,$font) format(\"woff2\")"
        }
    }

    /**
     * The bundled `mermaid.js` and the script that draws every `.mdr-mermaid` block, only for pages
     * that have one. Both run under the page's nonce, so the CSP needs nothing beyond `script-src 'nonce-…'`.
     */
    private fun mermaidScripts(diff: RenderedDiff, nonce: String, restoreIdx: Int): String {
        if (!diff.bodyHtml.contains(MERMAID_CLASS)) return ""
        val theme = if (isDark()) "dark" else "default"
        return "<script nonce=\"$nonce\">" + mermaidJs + "</script>\n" +
            "<script nonce=\"$nonce\">" + MERMAID_RUN_JS + "mdrDrawDiagrams('$theme', $restoreIdx);</script>"
    }

    /** `mermaid.min.js`, pinned in `src/main/resources/pl/mdreview/mermaid` (see THIRD_PARTY_NOTICES.md). */
    private val mermaidJs: String by lazy {
        val js = HtmlPage::class.java.getResourceAsStream(MERMAID_JS)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Missing resource $MERMAID_JS")
        // The library is inlined in a <script>; make sure no string in it ends that element early.
        js.replace("</script", "<\\/script")
    }

    private fun isDark() = ColorUtil.isDark(EditorColorsManager.getInstance().globalScheme.defaultBackground)

    /** The calls that add the block buttons and turn on source editing, or nothing when the page has neither. */
    fun actionsJs(diff: RenderedDiff, actions: BlockActions): String = buttonsJs(diff, actions) + editingJs(actions)

    /** Double-click editing of the sides [actions] allows (FR-13), with the editor's hints. */
    private fun editingJs(actions: BlockActions): String {
        if (!actions.edits) return ""
        val save = (if (SystemInfo.isMac) "Cmd" else "Ctrl") + "+Enter"
        return "mdr.editing({left: ${actions.editLeft}, right: ${actions.editRight}}, ${jsString(actions.stamp)}, " +
            "${jsString(MarkdownReviewBundle.message("block.editor.hint", save))}, " +
            "${jsString(MarkdownReviewBundle.message("block.editor.refused"))});"
    }

    private fun buttonsJs(diff: RenderedDiff, actions: BlockActions): String {
        if (!actions.any) return ""
        val blocks = diff.blocks.filter { it.edit != null }.joinToString(",") { it.idx.toString() }
        fun button(action: BlockAction, enabled: Boolean): String {
            if (!enabled) return "null"
            return "{action: ${jsString(action.id)}, " +
                "label: ${jsString(MarkdownReviewBundle.message("block.${action.id}.text"))}, " +
                "title: ${jsString(MarkdownReviewBundle.message("block.${action.id}.description"))}}"
        }
        return "mdr.actions([$blocks], [${button(BlockAction.APPLY, actions.apply)}, " +
            "${button(BlockAction.REVERT, actions.revert)}], ${jsString(actions.stamp)});"
    }

    /** [text] as a JavaScript string literal that is also safe inside `<script>`. */
    fun jsString(text: String): String = buildString {
        append('"')
        for (c in text) {
            if (c.isLetterOrDigit() && c.code < 0x80 || c in " .,:;!?()-_") append(c)
            else append("\\u").append(String.format("%04x", c.code))
        }
        append('"')
    }

    fun imgSources(remoteImages: Boolean) = if (remoteImages) "file: data: https: http:" else "file: data:"

    fun themeCss(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        val dark = isDark()
        fun diffBg(key: com.intellij.openapi.editor.colors.TextAttributesKey, fallback: Color) =
            scheme.getAttributes(key)?.backgroundColor ?: fallback
        val removed = diffBg(DiffColors.DIFF_DELETED, if (dark) Color(0x484A4A) else Color(0xD6D6D6))
        val added = diffBg(DiffColors.DIFF_INSERTED, if (dark) Color(0x294436) else Color(0xBEE6BE))
        val modified = diffBg(DiffColors.DIFF_MODIFIED, if (dark) Color(0x385570) else Color(0xC2D8F2))
        // Removed blocks are red per spec, regardless of IntelliJ's grey "deleted" color.
        val removedRed = if (dark) Color(0x5C2B2B) else Color(0xF7D4D4)
        val derivedRemovedBg = if (isGrey(removed)) removedRed else removed
        // Dark schemes give "modified" and "inserted" about the same lightness, so a green word inside a blue
        // block barely stands out (Darcula: 1.04:1). In dark mode the block only gets a tint (its marker bar
        // already says "modified") and the word marks get a saturated color of their own.
        fun wordBg(c: Color) = if (dark) vivid(c) else ColorUtil.mix(c, fg, 0.15)
        // Colors the user set under Editor | Color Scheme | Markdown Review win over the derived ones.
        fun custom(key: com.intellij.openapi.editor.colors.TextAttributesKey) = MarkdownReviewColors.background(scheme, key)
        val removedBg = custom(MarkdownReviewColors.REMOVED_BLOCK) ?: derivedRemovedBg
        val addedBg = custom(MarkdownReviewColors.ADDED_BLOCK) ?: added
        val modifiedBg = custom(MarkdownReviewColors.MODIFIED_BLOCK) ?: if (dark) ColorUtil.mix(bg, modified, 0.4) else modified
        val removedWordBg = custom(MarkdownReviewColors.REMOVED_WORDS) ?: wordBg(derivedRemovedBg)
        val addedWordBg = custom(MarkdownReviewColors.ADDED_WORDS) ?: wordBg(added)
        val vars = mapOf(
            "--md-bg" to css(bg),
            "--md-fg" to css(fg),
            "--md-muted" to css(UIUtil.getContextHelpForeground()),
            "--md-border" to css(JBColor.border()),
            "--md-link" to css(JBUI.CurrentTheme.Link.Foreground.ENABLED),
            "--md-code-bg" to css(ColorUtil.mix(bg, fg, 0.06)),
            "--md-removed-bg" to css(removedBg),
            "--md-added-bg" to css(addedBg),
            "--md-modified-bg" to css(modifiedBg),
            "--md-placeholder-bg" to css(ColorUtil.mix(bg, fg, 0.03)),
            "--md-removed-word-bg" to css(removedWordBg),
            "--md-added-word-bg" to css(addedWordBg),
            "--md-font" to "'${UIUtil.getLabelFont().family}', system-ui, sans-serif",
            "--md-font-size" to "${UIUtil.getLabelFont().size}px",
            "--md-mono-font" to "'${scheme.editorFontName}', monospace",
        ) + Theme.alertVariables(dark)
        return ":root { color-scheme: ${if (dark) "dark" else "light"}; " +
            vars.entries.joinToString(" ") { "${it.key}: ${it.value};" } + " }"
    }

    private fun isGrey(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) < 16

    /** [c]'s hue, saturated and lightened enough to read as a word mark on a dark tinted block. */
    internal fun vivid(c: Color): Color {
        val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
        return Color(Color.HSBtoRGB(hsb[0], maxOf(hsb[1], 0.6f), maxOf(hsb[2], 0.42f)))
    }

    private fun css(c: Color) = "#" + ColorUtil.toHex(c)

    private const val MERMAID_JS = "/pl/mdreview/mermaid/mermaid.min.js"
    private const val MERMAID_CLASS = "mdr-mermaid"

    /** The start of a formula element's class attribute; escaped document text cannot contain the quote. */
    private const val MATH_CLASS = "\"mdr-math mdr-math-"
    private const val KATEX_DIR = "/pl/mdreview/katex"

    /** A KaTeX `@font-face` source list: WOFF2, WOFF and TrueType files under `fonts/`. */
    private val KATEX_FONT_SRC = Regex("""src:url\(fonts/([\w-]+)\.woff2\) format\("woff2"\)[^;}]*""")

    /**
     * Draws every formula with KaTeX, in batches so a long document does not block scrolling for long. A formula
     * KaTeX cannot parse keeps its source, marked `.mdr-math-failed` with KaTeX's message as its tooltip (and below a
     * block formula); for a changed block formula the row's source diff opens instead, with the message on top.
     * Drawing changes row heights, so the restored position is applied again at the end unless the user scrolled.
     */
    private const val MATH_RUN_JS = """
        function mdrDrawMath(restoreIdx) {
          var moved = false;
          ['wheel', 'keydown', 'mousedown', 'touchstart'].forEach(function(t) {
            window.addEventListener(t, function() { moved = true; }, {once: true, passive: true});
          });
          var all = Array.prototype.slice.call(document.querySelectorAll('.mdr-math'));
          var n = 0;
          function draw(el) {
            var tex = el.textContent;
            var display = el.classList.contains('mdr-math-display');
            try {
              katex.render(tex, el, {displayMode: display, throwOnError: true, trust: false, strict: 'ignore'});
              el.classList.add('mdr-math-drawn');
            } catch (err) {
              el.textContent = tex;
              var text = String((err && err.message) || err);
              el.classList.add('mdr-math-failed');
              el.title = text;
              if (!display) return;
              var msg = document.createElement('div');
              msg.className = 'mdr-math-error';
              msg.textContent = text;
              el.after(msg);
              var row = el.closest('.mdr-math-row');
              var source = row && row.querySelector(':scope > details.mdr-math-source');
              if (source) {
                source.querySelector('summary').after(msg.cloneNode(true));
                source.open = true;
              }
            }
          }
          function next() {
            var end = Math.min(n + 50, all.length);
            for (; n < end; n++) draw(all[n]);
            if (n < all.length) setTimeout(next, 0);
            else if (!moved) mdr.restore(restoreIdx);
          }
          next();
        }
    """

    /**
     * Draws the diagrams one at a time, so a large one does not block scrolling for long. A diagram that
     * Mermaid cannot parse keeps its source with Mermaid's message above it; for a changed diagram the
     * row's source diff opens instead, with the message on top. Drawing changes row heights, so the
     * restored position is applied again at the end unless the user has scrolled meanwhile.
     */
    private const val MERMAID_RUN_JS = """
        function mdrDrawDiagrams(theme, restoreIdx) {
          var moved = false;
          ['wheel', 'keydown', 'mousedown', 'touchstart'].forEach(function(t) {
            window.addEventListener(t, function() { moved = true; }, {once: true, passive: true});
          });
          var font = getComputedStyle(document.documentElement).getPropertyValue('--md-font');
          mermaid.initialize({startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, theme: theme, fontFamily: font});
          var blocks = Array.prototype.slice.call(document.querySelectorAll('.mdr-mermaid'));
          var n = 0;
          function next() {
            if (n >= blocks.length) { if (!moved) mdr.restore(restoreIdx); return; }
            var el = blocks[n];
            var id = 'mdr-mermaid-' + n++;
            var code = el.querySelector('.mdr-mermaid-code');
            mermaid.render(id, code.textContent).then(function(out) {
              var box = document.createElement('div');
              box.className = 'mdr-mermaid-svg';
              box.innerHTML = out.svg;
              el.insertBefore(box, code);
              el.classList.add('mdr-mermaid-drawn');
            }, function(err) {
              var stray = document.getElementById('d' + id);
              if (stray) stray.remove();
              var msg = document.createElement('div');
              msg.className = 'mdr-mermaid-error';
              msg.textContent = String((err && err.message) || err);
              el.insertBefore(msg, code);
              el.classList.add('mdr-mermaid-failed');
              var row = el.closest('.mdr-mermaid-row');
              var source = row && row.querySelector(':scope > details.mdr-mermaid-source');
              if (source) {
                // The drawings are hidden while the source diff is open: repeat the message there.
                var note = msg.cloneNode(true);
                source.querySelector('summary').after(note);
                source.open = true;
              }
            }).then(function() { setTimeout(next, 0); });
          }
          next();
        }
    """

    private const val PANEL_CSS = """
        .mdr-current, .mdr-current > [data-side] { outline: 2px solid var(--md-link); outline-offset: -2px; }
        [data-side][data-line] { cursor: text; }
        .mdr-merged .mdr-item { position: relative; }
        .mdr-item:hover > .mdr-actions { opacity: 1; }
        .mdr-editing > :not(.mdr-editor) { display: none; }
        .mdr-editor { padding: 4px 0; }
        .mdr-editor textarea {
          box-sizing: border-box; width: 100%; resize: vertical; padding: 6px 8px; border-radius: 4px;
          border: 1px solid var(--md-link); background: var(--md-bg); color: var(--md-fg);
          font-family: var(--md-mono-font, monospace); font-size: 0.9em; line-height: 1.45; outline: none;
        }
        .mdr-editor-hint { margin-top: 2px; font-size: 0.8em; color: var(--md-muted); }
        .mdr-editor-refused { color: var(--md-alert-caution); }
    """

    /**
     * Messages to Kotlin are `action|arg|arg` strings:
     * `scroll|<idx>` topmost visible block, `source|<side>|<line>` Ctrl/Cmd+click on a block,
     * `link|<href>` external link click, `edit|<revert|apply>|<idx>|<stamp>` a block button click,
     * `open|<side>|<idx>|<stamp>` a double-click that asks for a block's source (answered with `mdr.openEditor`),
     * `edit|edit-<side>|<idx>|<stamp>|<markdown>` saving the editor, `closed` the editor closed without saving.
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
          var stamp = '';
          var editSides = {}, editHint = '', editRefused = '', asked = null, editor = null;
          function closeEditor() {
            if (!editor) return;
            editor.cell.classList.remove('mdr-editing');
            editor.box.remove();
            editor = null;
            mdrSend('closed');
          }
          document.addEventListener('dblclick', function(e) {
            if (editor || e.ctrlKey || e.metaKey || e.target.closest('a, button, summary, .mdr-editor')) return;
            var cell = e.target.closest('[data-side][data-line]');
            var row = cell && cell.closest('[data-idx]');
            if (!row || !editSides[cell.getAttribute('data-side')]) return;
            e.preventDefault();
            var sel = window.getSelection();
            if (sel) sel.removeAllRanges();
            asked = {cell: cell, side: cell.getAttribute('data-side'), idx: row.getAttribute('data-idx')};
            mdrSend('open|' + asked.side + '|' + asked.idx + '|' + stamp);
          });
          document.addEventListener('click', function(e) {
            var button = e.target.closest('.mdr-actions button[data-action]');
            if (button) {
              e.preventDefault();
              if (button.disabled) return;
              var row = button.closest('[data-idx]');
              var all = row.querySelectorAll('.mdr-actions button');
              for (var i = 0; i < all.length; i++) all[i].disabled = true;
              mdrSend('edit|' + button.getAttribute('data-action') + '|' + row.getAttribute('data-idx') + '|' + stamp);
              return;
            }
            var moved = e.target.closest('a.mdr-moved-link[data-goto]');
            if (moved) {
              e.preventDefault();
              mdr.goTo(moved.getAttribute('data-goto'));
              return;
            }
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
            editing: function(sides, pageStamp, hint, refused) {
              editSides = sides; stamp = pageStamp; editHint = hint; editRefused = refused;
            },
            openEditor: function(side, idx, text) {
              if (editor || !asked || asked.side !== side || String(asked.idx) !== String(idx) || !asked.cell.isConnected) return false;
              var cell = asked.cell;
              asked = null;
              var box = document.createElement('div');
              box.className = 'mdr-editor';
              var area = document.createElement('textarea');
              area.value = text;
              area.spellcheck = false;
              area.rows = Math.max(2, text.split('\n').length + 1);
              var note = document.createElement('div');
              note.className = 'mdr-editor-hint';
              note.textContent = editHint;
              box.appendChild(area);
              box.appendChild(note);
              cell.appendChild(box);
              cell.classList.add('mdr-editing');
              editor = {cell: cell, box: box, area: area, note: note};
              area.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') { e.preventDefault(); closeEditor(); return; }
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                  e.preventDefault();
                  if (area.readOnly) return;
                  area.readOnly = true;
                  mdrSend('edit|edit-' + side + '|' + idx + '|' + stamp + '|' + area.value);
                }
              });
              area.focus();
              return true;
            },
            editorRefused: function() {
              if (!editor) return;
              editor.area.readOnly = false;
              editor.note.textContent = editRefused;
              editor.note.classList.add('mdr-editor-refused');
            },
            goTo: function(idx) {
              var el = block(idx);
              if (!el) return;
              reveal(el);
              var cur = document.querySelectorAll('.mdr-current');
              for (var i = 0; i < cur.length; i++) cur[i].classList.remove('mdr-current');
              el.classList.add('mdr-current');
              box(el).scrollIntoView({block: 'center'});
            },
            actions: function(blocks, buttons, pageStamp) {
              stamp = pageStamp;
              for (var i = 0; i < blocks.length; i++) {
                var el = block(blocks[i]);
                if (!el) continue;
                var box = document.createElement('div');
                box.className = 'mdr-actions';
                for (var j = 0; j < buttons.length; j++) {
                  var spec = buttons[j];
                  if (!spec) continue;
                  var b = document.createElement('button');
                  b.className = 'mdr-' + spec.action;
                  b.textContent = spec.label;
                  b.title = spec.title;
                  b.setAttribute('data-action', spec.action);
                  b.setAttribute('data-block-id', 'b' + blocks[i]);
                  box.appendChild(b);
                }
                el.appendChild(box);
              }
            },
            restore: function(idx) {
              var el = idx > 0 ? block(idx) : null;
              if (el) { reveal(el); box(el).scrollIntoView({block: 'start'}); }
            }
          };
        })();
    """
}
