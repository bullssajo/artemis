package com.artemis.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Request to analyze a web page and extract keywords
 */
data class AnalyzeRequest(
    val url: String,
    val extractionMethod: ExtractionMethod = ExtractionMethod.AUTO,
    val language: String = "auto"
)

/**
 * Content extraction method
 */
enum class ExtractionMethod {
    AUTO,           // Automatically choose the best method
    READABILITY,    // Text-based content extraction (Mozilla Readability style)
    SCREENSHOT_OCR, // Screenshot + LLM vision analysis
    HYBRID          // Combine both methods
}

/**
 * Response containing extracted content and keywords
 */
data class AnalyzeResponse(
    val url: String,
    val title: String?,
    val description: String?,
    val summary: String,
    val keywords: List<Keyword>,
    val extractedContent: ExtractedContent,
    val metadata: PageMetadata,
    val analyzedAt: Instant = Instant.now()
)

/**
 * SEO Keyword with relevance score
 */
data class Keyword(
    val term: String,
    val relevance: Double,      // 0.0 to 1.0
    val category: KeywordCategory,
    val searchVolume: String? = null  // Optional: high, medium, low
)

enum class KeywordCategory {
    PRIMARY,        // Main topic keywords
    SECONDARY,      // Supporting keywords
    LONG_TAIL,      // Long-tail keywords for specific queries
    BRAND,          // Brand or entity names
    ACTION          // Action-oriented keywords (buy, learn, etc.)
}

/**
 * Extracted content from the page
 */
data class ExtractedContent(
    val mainText: String,
    val headings: List<String>,
    val links: List<ExtractedLink>,
    val images: List<ExtractedImage>,
    val textLength: Int,
    val extractionMethod: ExtractionMethod
)

data class ExtractedLink(
    val text: String,
    val href: String,
    val isInternal: Boolean
)

data class ExtractedImage(
    val src: String,
    val alt: String?,
    val title: String?
)

/**
 * Page metadata
 */
data class PageMetadata(
    val title: String?,
    val metaDescription: String?,
    val metaKeywords: List<String>,
    val ogTags: Map<String, String>,
    val canonicalUrl: String?,
    val language: String?,
    val author: String?,
    val publishedDate: String?,
    val domain: String
)

/**
 * Internal model for raw page data
 */
data class RawPageData(
    val url: String,
    val html: String,
    val screenshot: ByteArray? = null,
    val loadTimeMs: Long,
    val finalUrl: String  // After redirects
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawPageData
        return url == other.url && finalUrl == other.finalUrl
    }

    override fun hashCode(): Int {
        return 31 * url.hashCode() + finalUrl.hashCode()
    }
}

/**
 * LLM Analysis Result
 */
data class LlmAnalysisResult(
    val summary: String,
    val keywords: List<Keyword>,
    val mainTopics: List<String>,
    val targetAudience: String?,
    val contentType: String?
)
