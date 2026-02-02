package com.artemis.controller

import com.artemis.model.*
import com.artemis.service.KeywordExtractionService
import com.artemis.service.LlmException
import com.artemis.service.PageFetchException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1")
class KeywordExtractionController(
    private val keywordExtractionService: KeywordExtractionService
) {

    /**
     * Analyze a URL and extract SEO keywords
     */
    @PostMapping("/analyze")
    fun analyzeUrl(
        @Valid @RequestBody request: AnalyzeUrlRequest
    ): ResponseEntity<AnalyzeResponse> {
        logger.info { "Received analyze request for URL: ${request.url}" }

        val analyzeRequest = AnalyzeRequest(
            url = request.url,
            extractionMethod = request.extractionMethod ?: ExtractionMethod.AUTO,
            language = request.language ?: "auto"
        )

        val response = keywordExtractionService.analyzeUrl(analyzeRequest)
        return ResponseEntity.ok(response)
    }

    /**
     * Extract content only (without keyword analysis)
     */
    @GetMapping("/extract")
    fun extractContent(
        @RequestParam @NotBlank url: String
    ): ResponseEntity<ExtractedContent> {
        logger.info { "Received extract request for URL: $url" }

        val content = keywordExtractionService.extractContentOnly(url)
        return ResponseEntity.ok(content)
    }

    /**
     * Get metadata only
     */
    @GetMapping("/metadata")
    fun getMetadata(
        @RequestParam @NotBlank url: String
    ): ResponseEntity<PageMetadata> {
        logger.info { "Received metadata request for URL: $url" }

        val metadata = keywordExtractionService.getMetadataOnly(url)
        return ResponseEntity.ok(metadata)
    }

    /**
     * Batch analyze multiple URLs
     */
    @PostMapping("/analyze/batch")
    fun analyzeBatch(
        @Valid @RequestBody request: BatchAnalyzeRequest
    ): ResponseEntity<BatchAnalyzeResponse> {
        logger.info { "Received batch analyze request for ${request.urls.size} URLs" }

        val analyzeRequests = request.urls.map { url ->
            AnalyzeRequest(
                url = url,
                extractionMethod = request.extractionMethod ?: ExtractionMethod.AUTO,
                language = request.language ?: "auto"
            )
        }

        val results = keywordExtractionService.analyzeUrls(analyzeRequests)

        val successResults = mutableListOf<AnalyzeResponse>()
        val failedResults = mutableListOf<FailedAnalysis>()

        results.forEachIndexed { index, result ->
            result.onSuccess { successResults.add(it) }
            result.onFailure { error ->
                failedResults.add(
                    FailedAnalysis(
                        url = request.urls[index],
                        error = error.message ?: "Unknown error"
                    )
                )
            }
        }

        return ResponseEntity.ok(
            BatchAnalyzeResponse(
                successful = successResults,
                failed = failedResults,
                totalRequested = request.urls.size,
                totalSuccessful = successResults.size,
                totalFailed = failedResults.size
            )
        )
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    fun healthCheck(): ResponseEntity<HealthResponse> {
        return ResponseEntity.ok(
            HealthResponse(
                status = "UP",
                timestamp = Instant.now()
            )
        )
    }
}

// Request DTOs
data class AnalyzeUrlRequest(
    @field:NotBlank(message = "URL is required")
    val url: String,
    val extractionMethod: ExtractionMethod? = null,
    val language: String? = null
)

data class BatchAnalyzeRequest(
    val urls: List<String>,
    val extractionMethod: ExtractionMethod? = null,
    val language: String? = null
)

// Response DTOs
data class BatchAnalyzeResponse(
    val successful: List<AnalyzeResponse>,
    val failed: List<FailedAnalysis>,
    val totalRequested: Int,
    val totalSuccessful: Int,
    val totalFailed: Int
)

data class FailedAnalysis(
    val url: String,
    val error: String
)

data class HealthResponse(
    val status: String,
    val timestamp: Instant
)

// Error response
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
    val path: String? = null
)

/**
 * Global exception handler
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(PageFetchException::class)
    fun handlePageFetchException(e: PageFetchException): ResponseEntity<ErrorResponse> {
        logger.warn { "Page fetch error: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(
                ErrorResponse(
                    error = "PAGE_FETCH_ERROR",
                    message = e.message ?: "Failed to fetch the page"
                )
            )
    }

    @ExceptionHandler(LlmException::class)
    fun handleLlmException(e: LlmException): ResponseEntity<ErrorResponse> {
        logger.error { "LLM error: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                ErrorResponse(
                    error = "LLM_ERROR",
                    message = e.message ?: "LLM service error"
                )
            )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.warn { "Invalid argument: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponse(
                    error = "INVALID_REQUEST",
                    message = e.message ?: "Invalid request"
                )
            )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error(e) { "Unexpected error: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ErrorResponse(
                    error = "INTERNAL_ERROR",
                    message = "An unexpected error occurred"
                )
            )
    }
}
