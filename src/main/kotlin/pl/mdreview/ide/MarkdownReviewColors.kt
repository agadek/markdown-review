package pl.mdreview.ide

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import java.awt.Color
import javax.swing.Icon

/**
 * Colors of the rendered diff, set per color scheme under Settings | Editor | Color Scheme | Markdown Review.
 * The keys have no defaults: a color left unset is derived from the scheme's Diff colors (see [HtmlPage.themeCss]),
 * so the rendered diff follows the scheme until the user picks a color of their own.
 */
object MarkdownReviewColors {
    val ADDED_BLOCK = key("ADDED_BLOCK")
    val REMOVED_BLOCK = key("REMOVED_BLOCK")
    val MODIFIED_BLOCK = key("MODIFIED_BLOCK")
    val ADDED_WORDS = key("ADDED_WORDS")
    val REMOVED_WORDS = key("REMOVED_WORDS")

    private fun key(name: String) = TextAttributesKey.createTextAttributesKey("MARKDOWN_REVIEW_$name")

    /** The background the user set for [key] in [scheme], or null to use the derived color. */
    fun background(scheme: EditorColorsScheme, key: TextAttributesKey): Color? = scheme.getAttributes(key)?.backgroundColor
}

class MarkdownReviewColorSettingsPage : ColorSettingsPage {
    private val tags = mapOf(
        "added" to MarkdownReviewColors.ADDED_BLOCK,
        "removed" to MarkdownReviewColors.REMOVED_BLOCK,
        "modified" to MarkdownReviewColors.MODIFIED_BLOCK,
        "addedWords" to MarkdownReviewColors.ADDED_WORDS,
        "removedWords" to MarkdownReviewColors.REMOVED_WORDS,
    )

    override fun getDisplayName(): String = MarkdownReviewBundle.message("colors.display.name")

    override fun getIcon(): Icon? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor(MarkdownReviewBundle.message("colors.added.block"), MarkdownReviewColors.ADDED_BLOCK),
        AttributesDescriptor(MarkdownReviewBundle.message("colors.removed.block"), MarkdownReviewColors.REMOVED_BLOCK),
        AttributesDescriptor(MarkdownReviewBundle.message("colors.modified.block"), MarkdownReviewColors.MODIFIED_BLOCK),
        AttributesDescriptor(MarkdownReviewBundle.message("colors.added.words"), MarkdownReviewColors.ADDED_WORDS),
        AttributesDescriptor(MarkdownReviewBundle.message("colors.removed.words"), MarkdownReviewColors.REMOVED_WORDS),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getDemoText(): String = MarkdownReviewBundle.message("colors.demo")

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = tags
}
