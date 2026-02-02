package com.artemis.service

import com.artemis.config.LlmProperties
import com.artemis.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Service
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class LlmService(
    private val llmProperties: LlmProperties,
    private val okHttpClient: OkHttpClient
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    companion object {
        private const val KEYWORD_EXTRACTION_PROMPT = """
You are an SEO expert. Analyze the following web page content and extract relevant keywords for SEO optimization.

Provide your response in the following JSON format:
{
  "summary": "A concise 2-3 sentence summary of the page content",
  "keywords": [
    {"term": "keyword", "relevance": 0.95, "category": "PRIMARY"},
    {"term": "another keyword", "relevance": 0.85, "category": "SECONDARY"},
    {"term": "long tail keyword phrase", "relevance": 0.75, "category": "LONG_TAIL"},
    {"term": "brand name", "relevance": 0.70, "category": "BRAND"},
    {"term": "action keyword", "relevance": 0.65, "category": "ACTION"}
  ],
  "mainTopics": ["topic1", "topic2", "topic3"],
  "targetAudience": "Description of the target audience",
  "contentType": "article/product/service/blog/news/etc"
}

Guidelines:
- Extract 10-20 relevant keywords
- PRIMARY keywords are main topic keywords (1-3 keywords)
- SECONDARY keywords support the main topic (3-5 keywords)
- LONG_TAIL keywords are specific phrases (3-5 keywords)
- BRAND keywords are company/product names mentioned (if any)
- ACTION keywords are actionable terms like "buy", "learn", "download" (if applicable)
- Relevance scores should be between 0.0 and 1.0
- Focus on keywords that would help the page rank well in search engines

Content to analyze:
"""

        private const val SCREENSHOT_ANALYSIS_PROMPT = """
You are a web content analyst with computer vision capabilities. Analyze this screenshot of a web page.

Extract the main content visible on the page, ignoring:
- Navigation menus and headers
- Sidebars and advertisements
- Footer content
- Cookie banners and popups

Provide your analysis in the following JSON format:
{
  "extractedText": "The main content text visible on the page",
  "pageTitle": "The main title or heading of the page",
  "mainTopics": ["topic1", "topic2"],
  "visualElements": ["description of key images or graphics"],
  "contentType": "article/product/service/blog/news/etc",
  "language": "detected language code (e.g., en, ko, ja)"
}

Focus on the core content that would be valuable for SEO analysis.
"""
    }

    /**
     * Analyze text content using LLM to extract keywords
     */
    fun analyzeContent(content: ExtractedContent, metadata: PageMetadata): LlmAnalysisResult {
        logger.info { "Analyzing content with LLM (text length: ${content.textLength})" }

        val prompt = buildContentPrompt(content, metadata)
        val response = callLlm(prompt)

        return parseAnalysisResponse(response)
    }

    /**
     * Analyze screenshot using LLM Vision API
     */
    fun analyzeScreenshot(screenshot: ByteArray): ScreenshotAnalysisResult {
        logger.info { "Analyzing screenshot with LLM Vision (size: ${screenshot.size} bytes)" }

        val response = callLlmWithImage(SCREENSHOT_ANALYSIS_PROMPT, screenshot)
        return parseScreenshotResponse(response)
    }

    /**
     * Build the prompt for content analysis
     */
    private fun buildContentPrompt(content: ExtractedContent, metadata: PageMetadata): String {
        val contextBuilder = StringBuilder()

        metadata.title?.let { contextBuilder.appendLine("Page Title: $it") }
        metadata.metaDescription?.let { contextBuilder.appendLine("Meta Description: $it") }

        if (metadata.metaKeywords.isNotEmpty()) {
            contextBuilder.appendLine("Existing Keywords: ${metadata.metaKeywords.joinToString(", ")}")
        }

        if (content.headings.isNotEmpty()) {
            contextBuilder.appendLine("Headings: ${content.headings.take(10).joinToString(", ")}")
        }

        contextBuilder.appendLine("\nMain Content:")
        contextBuilder.appendLine(content.mainText.take(15000)) // Limit content length for API

        return KEYWORD_EXTRACTION_PROMPT + contextBuilder.toString()
    }

    /**
     * Call LLM API with text prompt
     */
    private fun callLlm(prompt: String): String {
        val requestBody = buildTextRequest(prompt)

        val request = Request.Builder()
            .url("${llmProperties.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${llmProperties.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return executeRequest(request)
    }

    /**
     * Call LLM API with image (Vision)
     */
    private fun callLlmWithImage(prompt: String, image: ByteArray): String {
        val base64Image = Base64.getEncoder().encodeToString(image)
        val requestBody = buildVisionRequest(prompt, base64Image)

        val request = Request.Builder()
            .url("${llmProperties.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${llmProperties.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return executeRequest(request)
    }

    /**
     * Build request body for text-only prompt
     */
    private fun buildTextRequest(prompt: String): String {
        val requestJson = buildJsonObject {
            put("model", llmProperties.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            put("temperature", 0.3)
            put("max_tokens", 4096)
        }
        return requestJson.toString()
    }

    /**
     * Build request body for vision request
     */
    private fun buildVisionRequest(prompt: String, base64Image: String): String {
        val requestJson = buildJsonObject {
            put("model", llmProperties.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/png;base64,$base64Image")
                                put("detail", "high")
                            }
                        }
                    }
                }
            }
            put("temperature", 0.3)
            put("max_tokens", 4096)
        }
        return requestJson.toString()
    }

    /**
     * Execute HTTP request and extract response content
     */
    private fun executeRequest(request: Request): String {
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error body"
            logger.error { "LLM API error: ${response.code} - $errorBody" }
            throw LlmException("LLM API error: ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw LlmException("Empty response from LLM API")

        return extractContentFromResponse(responseBody)
    }

    /**
     * Extract the content field from LLM response
     */
    private fun extractContentFromResponse(responseBody: String): String {
        return try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val choices = jsonResponse["choices"]?.jsonArray
                ?: throw LlmException("No choices in response")

            val firstChoice = choices.firstOrNull()?.jsonObject
                ?: throw LlmException("Empty choices array")

            val message = firstChoice["message"]?.jsonObject
                ?: throw LlmException("No message in choice")

            message["content"]?.jsonPrimitive?.content
                ?: throw LlmException("No content in message")
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse LLM response: $responseBody" }
            throw LlmException("Failed to parse LLM response", e)
        }
    }

    /**
     * Parse the analysis response JSON
     */
    private fun parseAnalysisResponse(response: String): LlmAnalysisResult {
        return try {
            // Extract JSON from response (handle markdown code blocks)
            val jsonString = extractJsonFromResponse(response)
            val jsonElement = json.parseToJsonElement(jsonString).jsonObject

            val keywords = jsonElement["keywords"]?.jsonArray?.mapNotNull { keywordJson ->
                try {
                    val obj = keywordJson.jsonObject
                    Keyword(
                        term = obj["term"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        relevance = obj["relevance"]?.jsonPrimitive?.double ?: 0.5,
                        category = try {
                            KeywordCategory.valueOf(
                                obj["category"]?.jsonPrimitive?.content ?: "SECONDARY"
                            )
                        } catch (e: Exception) {
                            KeywordCategory.SECONDARY
                        }
                    )
                } catch (e: Exception) {
                    logger.warn { "Failed to parse keyword: $keywordJson" }
                    null
                }
            } ?: emptyList()

            val mainTopics = jsonElement["mainTopics"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

            LlmAnalysisResult(
                summary = jsonElement["summary"]?.jsonPrimitive?.content ?: "",
                keywords = keywords,
                mainTopics = mainTopics,
                targetAudience = jsonElement["targetAudience"]?.jsonPrimitive?.contentOrNull,
                contentType = jsonElement["contentType"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse analysis response: $response" }
            // Return a basic result on parse failure
            LlmAnalysisResult(
                summary = "Failed to parse LLM response",
                keywords = emptyList(),
                mainTopics = emptyList(),
                targetAudience = null,
                contentType = null
            )
        }
    }

    /**
     * Parse screenshot analysis response
     */
    private fun parseScreenshotResponse(response: String): ScreenshotAnalysisResult {
        return try {
            val jsonString = extractJsonFromResponse(response)
            val jsonElement = json.parseToJsonElement(jsonString).jsonObject

            ScreenshotAnalysisResult(
                extractedText = jsonElement["extractedText"]?.jsonPrimitive?.content ?: "",
                pageTitle = jsonElement["pageTitle"]?.jsonPrimitive?.contentOrNull,
                mainTopics = jsonElement["mainTopics"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                visualElements = jsonElement["visualElements"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                contentType = jsonElement["contentType"]?.jsonPrimitive?.contentOrNull,
                language = jsonElement["language"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse screenshot response: $response" }
            ScreenshotAnalysisResult(
                extractedText = response,
                pageTitle = null,
                mainTopics = emptyList(),
                visualElements = emptyList(),
                contentType = null,
                language = null
            )
        }
    }

    /**
     * Extract JSON from response that might be wrapped in markdown code blocks
     */
    private fun extractJsonFromResponse(response: String): String {
        val trimmed = response.trim()

        // Check for markdown code block
        if (trimmed.startsWith("```")) {
            val lines = trimmed.lines()
            val jsonLines = lines.drop(1).dropLast(1)
            return jsonLines.joinToString("\n")
        }

        // Check if it starts with { or [
        val jsonStart = trimmed.indexOfFirst { it == '{' || it == '[' }
        if (jsonStart >= 0) {
            val jsonEnd = trimmed.lastIndexOfAny(charArrayOf('}', ']'))
            if (jsonEnd > jsonStart) {
                return trimmed.substring(jsonStart, jsonEnd + 1)
            }
        }

        return trimmed
    }
}

/**
 * Result from screenshot analysis
 */
data class ScreenshotAnalysisResult(
    val extractedText: String,
    val pageTitle: String?,
    val mainTopics: List<String>,
    val visualElements: List<String>,
    val contentType: String?,
    val language: String?
)

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
