package com.nexus.aichat

import com.nexus.aichat.ui.chat.components.MarkdownBlock
import com.nexus.aichat.ui.chat.components.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown parsing tests.
 *
 * These matter more than they look: the renderer is fed *streaming* text, so half of these cases are
 * deliberately malformed input. A parser that throws on an unterminated fence crashes the app mid-answer,
 * which is the worst possible moment to lose a conversation.
 */
class MarkdownParserTest {

    @Test
    fun `parses headings with level`() {
        val blocks = MarkdownParser.parse("## Results\n\ntext")
        assertEquals(MarkdownBlock.Heading(2, "Results"), blocks.first())
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun `unterminated fence still renders as code`() {
        val blocks = MarkdownParser.parse("Here:\n```kotlin\nval x = 1")
        val code = blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("val x = 1"))
    }

    @Test
    fun `a sentence containing pipes is not a table`() {
        val blocks = MarkdownParser.parse("Use a | b for alternatives")
        assertTrue(blocks.none { it is MarkdownBlock.Table })
    }

    @Test
    fun `header plus separator becomes a table`() {
        val blocks = MarkdownParser.parse("| Model | Latency |\n| --- | --- |\n| gpt-5.2 | 820 |")
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("Model", "Latency"), table.headers)
        assertEquals(listOf("gpt-5.2", "820"), table.rows.single())
    }

    @Test
    fun `consecutive bullets become one block`() {
        val blocks = MarkdownParser.parse("- one\n- two\n- three")
        val bullets = blocks.filterIsInstance<MarkdownBlock.Bullets>().single()
        assertEquals(3, bullets.items.size)
    }

    @Test
    fun `display math is captured verbatim`() {
        val blocks = MarkdownParser.parse("$$\nE = mc^2\n$$")
        assertEquals("E = mc^2", blocks.filterIsInstance<MarkdownBlock.Math>().single().latex.trim())
    }

    @Test
    fun `blockquote consumes its continuation lines`() {
        val blocks = MarkdownParser.parse("> first\n> second\n\nafter")
        val quote = blocks.filterIsInstance<MarkdownBlock.Quote>().single()
        assertTrue(quote.text.contains("first") && quote.text.contains("second"))
    }

    @Test
    fun `empty input produces no blocks`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("   \n  ").isEmpty())
    }
}
