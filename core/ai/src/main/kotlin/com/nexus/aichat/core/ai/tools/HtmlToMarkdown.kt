package com.nexus.aichat.core.ai.tools

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

/**
 * HTML -> Markdown with the corpus that actually matters for an LLM: headings, lists, tables,
 * links, code and quotes. Boilerplate (nav, ads, cookie banners, scripts) is removed first, because
 * a naive `element.text()` on a news page is 70% chrome and burns context for nothing.
 */
object HtmlToMarkdown {

    private val STRIP_SELECTORS = listOf(
        "script", "style", "noscript", "iframe", "svg", "canvas", "form",
        "nav", "header", "footer", "aside", "template",
        "[role=navigation]", "[role=banner]", "[role=contentinfo]",
        ".cookie-banner", ".newsletter", ".advertisement", ".ad", ".social-share",
    )

    private val MAIN_SELECTORS = listOf("article", "main", "[role=main]", "#content", ".post-content", ".article-body")

    fun convert(html: String, baseUri: String, maxChars: Int): String {
        val document = org.jsoup.Jsoup.parse(html, baseUri)
        document.outputSettings().prettyPrint(false)
        STRIP_SELECTORS.forEach { selector -> document.select(selector).remove() }

        val title = document.title().takeIf { it.isNotBlank() }
        val root = pickMainContent(document, html.length)

        val body = renderChildren(root)
            .replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("[ \t]{2,}"), " ")
            .trim()

        val out = buildString {
            title?.let { appendLine("# $it"); appendLine() }
            append(body)
        }
        return if (out.length <= maxChars) out else out.take(maxChars) + "\n\n[... truncated ...]"
    }

    /** Larger documents get heuristic main-content detection; small ones are rendered whole. */
    private fun pickMainContent(document: Document, htmlLength: Int): Element {
        if (htmlLength < 40_000) return document.body()
        MAIN_SELECTORS.forEach { selector ->
            val candidate = document.selectFirst(selector)
            if (candidate != null && candidate.text().length > 400) return candidate
        }
        // Fall back to the densest <div>: main content usually has the best text-to-markup ratio.
        return document.body().select("div").maxByOrNull { element ->
            element.ownText().length + element.select("p").sumOf { it.text().length }
        } ?: document.body()
    }

    private fun renderChildren(element: Element): String = element.childNodes().joinToString("") { renderNode(it) }

    private fun renderNode(node: Node): String = when (node) {
        is TextNode -> node.text().replace(Regex("\\s+"), " ")
        is Element -> renderElement(node)
        else -> ""
    }

    private fun renderElement(element: Element): String {
        val tag = element.tagName().lowercase()
        return when (tag) {
            "h1" -> block("# ${element.text()}", element)
            "h2" -> block("## ${element.text()}", element)
            "h3" -> block("### ${element.text()}", element)
            "h4" -> block("#### ${element.text()}", element)
            "h5" -> block("##### ${element.text()}", element)
            "h6" -> block("###### ${element.text()}", element)
            "p", "div", "section", "article", "main", "figure" -> block(renderChildren(element), element)
            "br" -> "\n"
            "hr" -> "\n---\n\n"
            "strong", "b" -> wrap("**", renderChildren(element), element)
            "em", "i" -> wrap("*", renderChildren(element), element)
            "del", "s" -> wrap("~~", renderChildren(element), element)
            "code", "kbd", "samp" -> if (element.parent()?.tagName() == "pre") renderChildren(element) else wrap("`", element.text(), element)
            "pre" -> block("```\n${element.wholeText().trimEnd()}\n```", element)
            "blockquote" -> block(
                renderChildren(element).trim().lines().joinToString("\n") { "> $it" },
                element,
            )
            "a" -> {
                val href = element.absUrl("href").ifBlank { element.attr("href") }
                val label = element.text().ifBlank { href }
                if (href.isBlank() || href.startsWith("javascript:")) label else "[$label]($href)"
            }
            "img" -> {
                val src = element.absUrl("src").ifBlank { element.attr("src") }
                val alt = element.attr("alt")
                if (src.isBlank()) "" else "![${alt.ifBlank { "image" }}]($src)"
            }
            "ul" -> block(renderList(element, ordered = false), element)
            "ol" -> block(renderList(element, ordered = true), element)
            "li" -> renderChildren(element)
            "table" -> block(renderTable(element), element)
            "dl" -> block(
                element.select("dt, dd").joinToString("\n") { child ->
                    if (child.tagName() == "dt") "**${child.text()}**" else ": ${child.text()}"
                },
                element,
            )
            else -> renderChildren(element)
        }
    }

    private fun renderList(element: Element, ordered: Boolean): String =
        element.select("> li").mapIndexed { index, item ->
            val marker = if (ordered) "${index + 1}." else "-"
            val text = renderChildren(item).trim().replace("\n", " ")
            "$marker $text"
        }.joinToString("\n")

    private fun renderTable(table: Element): String {
        val rows: Elements = table.select("tr")
        if (rows.isEmpty()) return ""
        val matrix = rows.map { row -> row.select("th, td").map { it.text().trim().replace("|", "\\|") } }
        val width = matrix.maxOf { it.size }
        if (width == 0) return ""
        return buildString {
            matrix.forEachIndexed { index, cells ->
                append("| ").append((cells + List(width - cells.size) { "" }).joinToString(" | ")).append(" |").append('\n')
                if (index == 0) {
                    append("| ").append(List(width) { "---" }.joinToString(" | ")).append(" |").append('\n')
                }
            }
        }.trimEnd()
    }

    private fun block(content: String, element: Element): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return ""
        val needsSpacing = element.tagName() !in setOf("li", "td", "th")
        return if (needsSpacing) "\n\n$trimmed\n\n" else trimmed
    }

    private fun wrap(marker: String, content: String, element: Element): String {
        val trimmed = content.trim()
        return if (trimmed.isEmpty()) "" else "$marker$trimmed$marker"
    }

    /** Extracts readable metadata for citations. */
    fun metadata(html: String, baseUri: String): PageMetadata {
        val document = org.jsoup.Jsoup.parse(html, baseUri)
        fun meta(name: String): String? = document.selectFirst("meta[name=$name], meta[property=$name]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
        return PageMetadata(
            title = meta("og:title") ?: document.title().takeIf { it.isNotBlank() },
            description = meta("og:description") ?: meta("description"),
            siteName = meta("og:site_name"),
            publishedAt = meta("article:published_time"),
            canonicalUrl = document.selectFirst("link[rel=canonical]")?.absUrl("href")?.takeIf { it.isNotBlank() },
        )
    }

    data class PageMetadata(
        val title: String?,
        val description: String?,
        val siteName: String?,
        val publishedAt: String?,
        val canonicalUrl: String?,
    )
}
