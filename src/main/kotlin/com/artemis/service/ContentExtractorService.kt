package com.artemis.service

import com.artemis.config.ExtractionProperties
import com.artemis.model.*
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Service
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Content extraction service implementing Mozilla Readability-style algorithm
 * Removes boilerplate content and extracts main article content
 */
@Service
class ContentExtractorService(
    private val extractionProperties: ExtractionProperties
) {

    companion object {
        // Elements to remove (noise)
        private val UNLIKELY_CANDIDATES = listOf(
            "banner", "breadcrumbs", "combx", "comment", "community", "cover-wrap",
            "disqus", "extra", "footer", "gdpr", "header", "legends", "menu",
            "related", "remark", "replies", "rss", "shoutbox", "sidebar", "skyscraper",
            "social", "sponsor", "supplemental", "ad-break", "agegate", "pagination",
            "pager", "popup", "yom-hierarchical", "navigation", "nav", "cookie",
            "advertisement", "share", "newsletter", "subscription", "promo"
        )

        // Positive indicators for content
        private val POSITIVE_INDICATORS = listOf(
            "article", "body", "content", "entry", "hentry", "h-entry", "main",
            "page", "pagination", "post", "text", "blog", "story"
        )

        // Negative indicators for content
        private val NEGATIVE_INDICATORS = listOf(
            "hidden", "hid", "banner", "combx", "comment", "com-", "contact",
            "foot", "footer", "footnote", "gdpr", "masthead", "media", "meta",
            "outbrain", "promo", "related", "scroll", "share", "shoutbox",
            "sidebar", "skyscraper", "sponsor", "shopping", "tags", "tool",
            "widget", "sidebar", "aside"
        )

        // Tags that typically contain content
        private val CONTENT_TAGS = setOf(
            "article", "main", "section", "div", "td", "pre"
        )

        // Tags to preserve in output
        private val PRESERVE_TAGS = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "blockquote", "pre", "code", "table", "tr", "td", "th",
            "figure", "figcaption", "img", "a", "strong", "em", "b", "i"
        )
    }

    /**
     * Extract main content from HTML using Readability-style algorithm
     */
    fun extractContent(rawPageData: RawPageData): ExtractedContent {
        logger.info { "Extracting content from: ${rawPageData.url}" }

        val document = Jsoup.parse(rawPageData.html, rawPageData.finalUrl)
        val baseUri = URI(rawPageData.finalUrl)

        // Clean the document
        val cleanedDoc = cleanDocument(document)

        // Extract metadata first
        val metadata = extractMetadata(document, baseUri)

        // Find main content element
        val mainContent = findMainContent(cleanedDoc)

        // Extract structured data
        val headings = extractHeadings(mainContent ?: cleanedDoc)
        val links = extractLinks(mainContent ?: cleanedDoc, baseUri)
        val images = extractImages(mainContent ?: cleanedDoc)

        // Extract clean text
        val mainText = if (mainContent != null) {
            cleanText(mainContent.text())
        } else {
            cleanText(cleanedDoc.body()?.text() ?: "")
        }

        val truncatedText = if (mainText.length > extractionProperties.maxContentLength) {
            mainText.take(extractionProperties.maxContentLength) + "..."
        } else {
            mainText
        }

        return ExtractedContent(
            mainText = truncatedText,
            headings = headings,
            links = links,
            images = images,
            textLength = mainText.length,
            extractionMethod = ExtractionMethod.READABILITY
        )
    }

    /**
     * Clean the document by removing noise elements
     */
    private fun cleanDocument(document: Document): Document {
        val doc = document.clone()

        // Remove script, style, and other non-content elements
        doc.select("script, style, noscript, iframe, svg, canvas, form, input, button").remove()
        doc.select("[style*='display:none'], [style*='display: none']").remove()
        doc.select("[hidden], .hidden, .hide").remove()

        // Remove elements with unlikely candidate classes/ids
        UNLIKELY_CANDIDATES.forEach { pattern ->
            doc.select("[class*=$pattern], [id*=$pattern]").forEach { element ->
                // Don't remove if it also has positive indicators
                val classAndId = "${element.className()} ${element.id()}".lowercase()
                val hasPositive = POSITIVE_INDICATORS.any { classAndId.contains(it) }
                if (!hasPositive) {
                    element.remove()
                }
            }
        }

        // Remove empty elements
        doc.select("div, span, p").forEach { element ->
            if (element.text().isBlank() && element.select("img, video, audio").isEmpty()) {
                element.remove()
            }
        }

        return doc
    }

    /**
     * Find the main content element using scoring algorithm
     */
    private fun findMainContent(document: Document): Element? {
        // First, try semantic elements
        val semanticContent = document.selectFirst("article, [role='main'], main")
        if (semanticContent != null && semanticContent.text().length > extractionProperties.minTextLength) {
            logger.debug { "Found content via semantic element: ${semanticContent.tagName()}" }
            return semanticContent
        }

        // Score all candidate elements
        val candidates = mutableMapOf<Element, Double>()

        document.select(CONTENT_TAGS.joinToString(", ")).forEach { element ->
            val score = scoreElement(element)
            if (score > 0) {
                candidates[element] = score
            }
        }

        // Find the best candidate
        val bestCandidate = candidates.maxByOrNull { it.value }
        if (bestCandidate != null && bestCandidate.value > 25) {
            logger.debug { "Found content via scoring: ${bestCandidate.key.tagName()} with score ${bestCandidate.value}" }
            return bestCandidate.key
        }

        // Fallback to body
        logger.debug { "Using body as fallback content source" }
        return document.body()
    }

    /**
     * Score an element based on content likelihood
     */
    private fun scoreElement(element: Element): Double {
        var score = 0.0

        val classAndId = "${element.className()} ${element.id()}".lowercase()

        // Positive scoring
        POSITIVE_INDICATORS.forEach { indicator ->
            if (classAndId.contains(indicator)) {
                score += 25
            }
        }

        // Negative scoring
        NEGATIVE_INDICATORS.forEach { indicator ->
            if (classAndId.contains(indicator)) {
                score -= 25
            }
        }

        // Score based on paragraph density
        val paragraphs = element.select("p")
        val textLength = element.text().length

        // Bonus for paragraphs
        score += paragraphs.size * 3

        // Bonus for text length
        score += (textLength / 100).coerceAtMost(50).toDouble()

        // Score based on link density (lower is better)
        val links = element.select("a")
        val linkTextLength = links.sumOf { it.text().length }
        val linkDensity = if (textLength > 0) linkTextLength.toDouble() / textLength else 1.0

        if (linkDensity > 0.5) {
            score -= 50
        } else if (linkDensity < 0.2) {
            score += 20
        }

        // Score based on heading presence
        val headings = element.select("h1, h2, h3")
        if (headings.isNotEmpty() && paragraphs.isNotEmpty()) {
            score += 20
        }

        return score
    }

    /**
     * Extract page metadata
     */
    fun extractMetadata(document: Document, baseUri: URI): PageMetadata {
        val ogTags = mutableMapOf<String, String>()
        document.select("meta[property^=og:]").forEach { meta ->
            val property = meta.attr("property").removePrefix("og:")
            val content = meta.attr("content")
            if (content.isNotBlank()) {
                ogTags[property] = content
            }
        }

        val metaKeywords = document.selectFirst("meta[name=keywords]")
            ?.attr("content")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return PageMetadata(
            title = document.title().takeIf { it.isNotBlank() }
                ?: ogTags["title"]
                ?: document.selectFirst("h1")?.text(),
            metaDescription = document.selectFirst("meta[name=description]")?.attr("content")
                ?: ogTags["description"],
            metaKeywords = metaKeywords,
            ogTags = ogTags,
            canonicalUrl = document.selectFirst("link[rel=canonical]")?.attr("href"),
            language = document.selectFirst("html")?.attr("lang")
                ?: document.selectFirst("meta[http-equiv=content-language]")?.attr("content"),
            author = document.selectFirst("meta[name=author]")?.attr("content")
                ?: document.selectFirst("[rel=author]")?.text(),
            publishedDate = document.selectFirst("meta[property='article:published_time']")?.attr("content")
                ?: document.selectFirst("time[datetime]")?.attr("datetime"),
            domain = baseUri.host
        )
    }

    /**
     * Extract headings from content
     */
    private fun extractHeadings(element: Element): List<String> {
        return element.select("h1, h2, h3, h4, h5, h6")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Extract links from content
     */
    private fun extractLinks(element: Element, baseUri: URI): List<ExtractedLink> {
        return element.select("a[href]")
            .mapNotNull { link ->
                val href = link.attr("abs:href")
                val text = link.text().trim()
                if (href.isNotBlank() && text.isNotBlank()) {
                    val linkUri = try {
                        URI(href)
                    } catch (e: Exception) {
                        null
                    }
                    ExtractedLink(
                        text = text,
                        href = href,
                        isInternal = linkUri?.host == baseUri.host
                    )
                } else null
            }
            .distinctBy { it.href }
            .take(100) // Limit to 100 links
    }

    /**
     * Extract images from content
     */
    private fun extractImages(element: Element): List<ExtractedImage> {
        return element.select("img[src]")
            .mapNotNull { img ->
                val src = img.attr("abs:src")
                if (src.isNotBlank()) {
                    ExtractedImage(
                        src = src,
                        alt = img.attr("alt").takeIf { it.isNotBlank() },
                        title = img.attr("title").takeIf { it.isNotBlank() }
                    )
                } else null
            }
            .distinctBy { it.src }
            .take(50) // Limit to 50 images
    }

    /**
     * Clean extracted text
     */
    private fun cleanText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")  // Normalize whitespace
            .replace(Regex("\\n{3,}"), "\n\n")  // Limit consecutive newlines
            .trim()
    }

    /**
     * Get clean HTML preserving structure
     */
    fun getCleanHtml(element: Element): String {
        return Jsoup.clean(
            element.html(),
            Safelist.relaxed()
                .addTags("article", "section", "header", "footer", "main", "aside", "figure", "figcaption")
                .addAttributes(":all", "class", "id")
        )
    }
}
