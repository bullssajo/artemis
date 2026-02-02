package com.artemis.service

import com.artemis.config.ExtractionProperties
import com.artemis.model.*
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Main service that orchestrates the keyword extraction workflow
 */
@Service
class KeywordExtractionService(
    private val webPageFetcherService: WebPageFetcherService,
    private val contentExtractorService: ContentExtractorService,
    private val llmService: LlmService,
    private val extractionProperties: ExtractionProperties
) {

    /**
     * Analyze a URL and extract SEO keywords
     */
    fun analyzeUrl(request: AnalyzeRequest): AnalyzeResponse {
        logger.info { "Starting analysis for URL: ${request.url}" }

        // Validate URL
        val validatedUrl = validateAndNormalizeUrl(request.url)

        // Determine extraction method
        val method = determineExtractionMethod(request.extractionMethod)

        // Fetch the page
        val rawPageData = webPageFetcherService.fetchPageWithRetry(
            url = validatedUrl,
            captureScreenshot = method == ExtractionMethod.SCREENSHOT_OCR || method == ExtractionMethod.HYBRID
        )

        // Extract content based on method
        val (extractedContent, metadata) = when (method) {
            ExtractionMethod.READABILITY -> extractWithReadability(rawPageData)
            ExtractionMethod.SCREENSHOT_OCR -> extractWithScreenshot(rawPageData)
            ExtractionMethod.HYBRID -> extractWithHybrid(rawPageData)
            ExtractionMethod.AUTO -> extractWithAuto(rawPageData)
        }

        // Analyze with LLM
        val llmResult = llmService.analyzeContent(extractedContent, metadata)

        // Build response
        return AnalyzeResponse(
            url = validatedUrl,
            title = metadata.title,
            description = metadata.metaDescription,
            summary = llmResult.summary,
            keywords = llmResult.keywords.sortedByDescending { it.relevance },
            extractedContent = extractedContent,
            metadata = metadata
        )
    }

    /**
     * Validate and normalize URL
     */
    private fun validateAndNormalizeUrl(url: String): String {
        val normalizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        try {
            val uri = URI(normalizedUrl)
            if (uri.host.isNullOrBlank()) {
                throw IllegalArgumentException("Invalid URL: missing host")
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL: ${e.message}")
        }

        return normalizedUrl
    }

    /**
     * Determine the best extraction method
     */
    private fun determineExtractionMethod(requested: ExtractionMethod): ExtractionMethod {
        return when (requested) {
            ExtractionMethod.AUTO -> ExtractionMethod.READABILITY // Default to readability
            else -> requested
        }
    }

    /**
     * Extract content using Readability algorithm
     */
    private fun extractWithReadability(rawPageData: RawPageData): Pair<ExtractedContent, PageMetadata> {
        logger.debug { "Extracting with Readability algorithm" }

        val content = contentExtractorService.extractContent(rawPageData)
        val document = org.jsoup.Jsoup.parse(rawPageData.html, rawPageData.finalUrl)
        val metadata = contentExtractorService.extractMetadata(document, URI(rawPageData.finalUrl))

        return content to metadata
    }

    /**
     * Extract content using screenshot OCR
     */
    private fun extractWithScreenshot(rawPageData: RawPageData): Pair<ExtractedContent, PageMetadata> {
        logger.debug { "Extracting with Screenshot OCR" }

        val screenshot = rawPageData.screenshot
            ?: throw IllegalStateException("Screenshot not available for OCR analysis")

        val screenshotResult = llmService.analyzeScreenshot(screenshot)

        val content = ExtractedContent(
            mainText = screenshotResult.extractedText,
            headings = listOfNotNull(screenshotResult.pageTitle),
            links = emptyList(),
            images = emptyList(),
            textLength = screenshotResult.extractedText.length,
            extractionMethod = ExtractionMethod.SCREENSHOT_OCR
        )

        // Also extract metadata from HTML for additional context
        val document = org.jsoup.Jsoup.parse(rawPageData.html, rawPageData.finalUrl)
        val metadata = contentExtractorService.extractMetadata(document, URI(rawPageData.finalUrl)).copy(
            language = screenshotResult.language ?: document.selectFirst("html")?.attr("lang")
        )

        return content to metadata
    }

    /**
     * Extract content using hybrid approach (both methods)
     */
    private fun extractWithHybrid(rawPageData: RawPageData): Pair<ExtractedContent, PageMetadata> {
        logger.debug { "Extracting with Hybrid approach" }

        // Get readability content
        val (readabilityContent, metadata) = extractWithReadability(rawPageData)

        // If screenshot is available, enhance with OCR
        val screenshot = rawPageData.screenshot
        val finalContent = if (screenshot != null) {
            try {
                val screenshotResult = llmService.analyzeScreenshot(screenshot)

                // Merge content - prefer readability but supplement with screenshot insights
                val combinedText = if (readabilityContent.mainText.length < extractionProperties.minTextLength) {
                    // Readability failed, use screenshot content
                    screenshotResult.extractedText
                } else {
                    readabilityContent.mainText
                }

                readabilityContent.copy(
                    mainText = combinedText,
                    extractionMethod = ExtractionMethod.HYBRID
                )
            } catch (e: Exception) {
                logger.warn(e) { "Screenshot analysis failed, falling back to readability only" }
                readabilityContent
            }
        } else {
            readabilityContent
        }

        return finalContent to metadata
    }

    /**
     * Auto-detect best extraction method
     */
    private fun extractWithAuto(rawPageData: RawPageData): Pair<ExtractedContent, PageMetadata> {
        logger.debug { "Auto-detecting extraction method" }

        // First try readability
        val (readabilityContent, metadata) = extractWithReadability(rawPageData)

        // If readability extraction got good content, use it
        if (readabilityContent.textLength >= extractionProperties.minTextLength) {
            return readabilityContent to metadata
        }

        // Otherwise, try hybrid if screenshot is available
        if (rawPageData.screenshot != null) {
            logger.info { "Readability extraction insufficient, trying hybrid approach" }
            return extractWithHybrid(rawPageData)
        }

        // Fallback to readability even if content is short
        return readabilityContent to metadata
    }

    /**
     * Batch analyze multiple URLs
     */
    fun analyzeUrls(requests: List<AnalyzeRequest>): List<Result<AnalyzeResponse>> {
        return requests.map { request ->
            try {
                Result.success(analyzeUrl(request))
            } catch (e: Exception) {
                logger.error(e) { "Failed to analyze URL: ${request.url}" }
                Result.failure(e)
            }
        }
    }

    /**
     * Get only extracted content without LLM analysis
     */
    fun extractContentOnly(url: String): ExtractedContent {
        val validatedUrl = validateAndNormalizeUrl(url)
        val rawPageData = webPageFetcherService.fetchPageWithRetry(validatedUrl, captureScreenshot = false)
        return contentExtractorService.extractContent(rawPageData)
    }

    /**
     * Get page metadata only
     */
    fun getMetadataOnly(url: String): PageMetadata {
        val validatedUrl = validateAndNormalizeUrl(url)
        val rawPageData = webPageFetcherService.fetchPageWithRetry(validatedUrl, captureScreenshot = false)
        val document = org.jsoup.Jsoup.parse(rawPageData.html, rawPageData.finalUrl)
        return contentExtractorService.extractMetadata(document, URI(rawPageData.finalUrl))
    }
}
