package com.nexus.aichat.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusType

/**
 * GFM-ish markdown renderer.
 *
 * Scope, stated honestly: this renders what models actually emit in chat - headings, paragraphs, bullet
 * and numbered lists, tables, blockquotes, fenced and inline code, bold/italic/strikethrough, links, and
 * display math - using Compose text primitives, with no HTML/WebView and no third-party markdown engine.
 * The trade-off is deliberate: a WebView renderer produces prettier LaTeX but breaks text selection,
 * theming, accessibility and scroll performance, all of which matter more in a chat feed.
 *
 * Parsing happens in two passes ([MarkdownParser]) because streaming text is almost always malformed
 * mid-flight: an unterminated ``` fence or a half-written table row must degrade to the previous frame's
 * appearance rather than throwing or flickering.
 */
@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { block -> Block(block) }
        }
    }
}

@Composable
private fun Block(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = InlineFormatter.format(block.text),
            style = NexusType.markdownHeading(block.level),
            color = MaterialTheme.colorScheme.onSurface,
        )

        is MarkdownBlock.Paragraph -> Text(
            text = InlineFormatter.format(block.text),
            style = NexusType.body(),
            color = MaterialTheme.colorScheme.onSurface,
        )

        is MarkdownBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\u2022", style = NexusType.body(), color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = InlineFormatter.format(item),
                        style = NexusType.body(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        is MarkdownBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${index + 1}.",
                        style = NexusType.body(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = InlineFormatter.format(item),
                        style = NexusType.body(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        is MarkdownBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
            )
            Text(
                text = InlineFormatter.format(block.text),
                style = NexusType.body(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is MarkdownBlock.Code -> CodeBlock(code = block.code, language = block.language)

        is MarkdownBlock.Math -> MathBlock(block.latex)

        is MarkdownBlock.Table -> MarkdownTable(block.headers, block.rows)

        MarkdownBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/**
 * Display math.
 *
 * There is no KaTeX on Android, and shipping a LaTeX engine to render the occasional formula is not a
 * trade worth making in a chat client. So a formula is rendered as a centred, selectable, monospaced
 * block with its operators preserved - readable, copyable into a real tool, and honest about what it is.
 */
@Composable
private fun MathBlock(latex: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .padding(vertical = 12.dp, horizontal = 12.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = latex.trim(),
            style = NexusType.codeBlock().copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

/** Tables scroll horizontally rather than squashing columns; long cells keep their own line breaks. */
@Composable
private fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)),
    ) {
        Row {
            headers.forEach { header ->
                Text(
                    text = InlineFormatter.format(header),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        text = InlineFormatter.format(cell),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// --- model ---------------------------------------------------------------------------------------

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullets(val items: List<String>) : MarkdownBlock
    data class Numbers(val items: List<String>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
    data class Math(val latex: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * Block-level parser.
 *
 * Tolerances that matter for a *streaming* feed:
 *  - an unterminated code fence renders as a code block that is still growing (not as a paragraph of
 *    backticks), which is what makes the cursor inside code look right;
 *  - a `|`-row only becomes a table once a separator row has been seen - otherwise a sentence
 *    containing pipes turns into a broken table mid-stream.
 */
object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val FENCE = Regex("^\\s*```\\s*([A-Za-z0-9_+\\-]*)\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val MATH_BLOCK = Regex("^\\s*\\$\\$(.+?)\\$\\$\\s*$")
    private val MATH_BLOCK_OPEN = Regex("^\\s*\\$\\$\\s*$")

    fun parse(markdown: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }

        val lines = markdown.replace("\r\n", "\n").split('\n')
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            // Fenced code (possibly still open - that is the streaming case).
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                val language = fence.groupValues[1].takeIf { it.isNotBlank() }
                val body = StringBuilder()
                index++
                while (index < lines.size && FENCE.matchEntire(lines[index]) == null) {
                    body.appendLine(lines[index])
                    index++
                }
                blocks += MarkdownBlock.Code(language, body.toString())
                index++ // consume the closing fence when present
                continue
            }

            // Display math on one line, or a $$…$$ block.
            MATH_BLOCK.matchEntire(line)?.let { match ->
                flushParagraph()
                blocks += MarkdownBlock.Math(match.groupValues[1])
                index++
                continue
            }
            if (MATH_BLOCK_OPEN.matches(line)) {
                flushParagraph()
                val body = StringBuilder()
                index++
                while (index < lines.size && !MATH_BLOCK_OPEN.matches(lines[index])) {
                    body.appendLine(lines[index])
                    index++
                }
                blocks += MarkdownBlock.Math(body.toString())
                index++
                continue
            }

            HEADING.matchEntire(line)?.let { match ->
                flushParagraph()
                blocks += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
                index++
                continue
            }

            if (line.trim() in setOf("---", "***", "___")) {
                flushParagraph()
                blocks += MarkdownBlock.Divider
                index++
                continue
            }

            if (line.trimStart().startsWith(">")) {
                flushParagraph()
                val quoted = StringBuilder()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quoted.appendLine(lines[index].trimStart().removePrefix(">").trim())
                    index++
                }
                blocks += MarkdownBlock.Quote(quoted.toString().trim())
                continue
            }

            // Table: header row + separator row.
            if (line.contains('|') && index + 1 < lines.size && TABLE_SEPARATOR.matches(lines[index + 1])) {
                flushParagraph()
                val headers = splitRow(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                    rows += splitRow(lines[index])
                    index++
                }
                blocks += MarkdownBlock.Table(headers, rows)
                continue
            }

            // Lists: a run of same-kind items becomes one block, so bullets do not each get a gap.
            if (BULLET.matches(line)) {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size && BULLET.matches(lines[index])) {
                    items += BULLET.matchEntire(lines[index])!!.groupValues[1]
                    index++
                }
                blocks += MarkdownBlock.Bullets(items)
                continue
            }
            if (NUMBERED.matches(line)) {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size && NUMBERED.matches(lines[index])) {
                    items += NUMBERED.matchEntire(lines[index])!!.groupValues[2]
                    index++
                }
                blocks += MarkdownBlock.Numbers(items)
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                index++
                continue
            }

            paragraph.appendLine(line)
            index++
        }

        flushParagraph()
        return blocks
    }

    private fun splitRow(line: String): List<String> = line
        .trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split('|')
        .map { it.trim() }
}

/**
 * Inline spans: `code`, **bold**, *italic*, ~~strike~~, [links](url) and $inline math$.
 *
 * The parser is intentionally non-recursive (no nested emphasis): model output in chat rarely nests, and
 * a regex pass that never backtracks keeps a long streaming message at a stable frame cost.
 */
object InlineFormatter {

    private val PATTERN = Regex(
        "(`[^`]+`)" +                                   // inline code
            "|(\\*\\*[^*]+\\*\\*)" +                    // bold
            "|(~~[^~]+~~)" +                            // strikethrough
            "|(\\*[^*\\n]+\\*)" +                       // italic
            "|(\\$[^$\\n]+\\$)" +                       // inline math
            "|(\\[[^\\]]+\\]\\([^)\\s]+\\))" +          // link
            "|(https?://\\S+)",                         // bare url
    )

    @Composable
    fun format(text: String): AnnotatedString {
        // Theme-dependent values are read here, in composition: `buildAnnotatedString` is an ordinary
        // builder, so a `MaterialTheme`/`ChatColors` read inside it would not even compile.
        val codeFont = NexusType.inlineCode().fontFamily
        val linkStyle = SpanStyle(color = ChatColors.info, textDecoration = TextDecoration.Underline)

        return buildAnnotatedString {
        var cursor = 0
        PATTERN.findAll(text).forEach { match ->
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("`") -> withStyle(SpanStyle(fontFamily = codeFont)) {
                    append(token.drop(1).dropLast(1))
                }
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(token.drop(2).dropLast(2))
                }
                token.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(token.drop(2).dropLast(2))
                }
                token.startsWith("$") -> withStyle(
                    SpanStyle(fontFamily = codeFont, fontStyle = FontStyle.Italic),
                ) { append(token.drop(1).dropLast(1)) }
                token.startsWith("[") && token.contains("](") -> {
                    val label = token.substringAfter('[').substringBefore("](")
                    withStyle(linkStyle) { append(label) }
                }
                token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.drop(1).dropLast(1))
                }
                token.startsWith("http") -> withStyle(linkStyle) { append(token) }
                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
        }
    }

    /** Plain text for previews (drawer rows, share intents) - spans stripped, never rendered. */
    fun strip(text: String): String = PATTERN.replace(text) { match ->
        val token = match.value
        when {
            token.startsWith("[") && token.contains("](") -> token.substringAfter('[').substringBefore("](")
            else -> token.trim('`', '*', '~', '$')
        }
    }
}
