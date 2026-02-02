package com.artemis.service

import com.artemis.config.ExtractionProperties
import com.artemis.config.WebDriverFactory
import com.artemis.model.RawPageData
import mu.KotlinLogging
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.WebDriverWait
import org.springframework.stereotype.Service
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class WebPageFetcherService(
    private val webDriverFactory: WebDriverFactory,
    private val extractionProperties: ExtractionProperties
) {

    /**
     * Fetch a web page using Selenium WebDriver
     * @param url The URL to fetch
     * @param captureScreenshot Whether to capture a screenshot
     * @return RawPageData containing HTML and optional screenshot
     */
    fun fetchPage(url: String, captureScreenshot: Boolean = true): RawPageData {
        val driver = webDriverFactory.createDriver()

        return try {
            logger.info { "Fetching page: $url" }
            val startTime = System.currentTimeMillis()

            driver.get(url)

            // Wait for page to be fully loaded
            waitForPageLoad(driver)

            val loadTimeMs = System.currentTimeMillis() - startTime
            logger.info { "Page loaded in ${loadTimeMs}ms: ${driver.currentUrl}" }

            val html = driver.pageSource
            val screenshot = if (captureScreenshot && extractionProperties.screenshot.enabled) {
                captureFullPageScreenshot(driver)
            } else {
                null
            }

            RawPageData(
                url = url,
                html = html,
                screenshot = screenshot,
                loadTimeMs = loadTimeMs,
                finalUrl = driver.currentUrl
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch page: $url" }
            throw PageFetchException("Failed to fetch page: $url", e)
        } finally {
            try {
                driver.quit()
            } catch (e: Exception) {
                logger.warn(e) { "Error closing WebDriver" }
            }
        }
    }

    /**
     * Wait for page to be fully loaded including JavaScript rendering
     */
    private fun waitForPageLoad(driver: WebDriver) {
        val wait = WebDriverWait(driver, Duration.ofSeconds(30))

        // Wait for document ready state
        wait.until { d ->
            val readyState = (d as org.openqa.selenium.JavascriptExecutor)
                .executeScript("return document.readyState")
            readyState == "complete"
        }

        // Additional wait for dynamic content
        try {
            Thread.sleep(1000) // Brief pause for JavaScript rendering

            // Wait for any pending AJAX requests (jQuery)
            val jsExecutor = driver as org.openqa.selenium.JavascriptExecutor
            wait.until { d ->
                val jQueryActive = try {
                    (d as org.openqa.selenium.JavascriptExecutor)
                        .executeScript("return (typeof jQuery !== 'undefined') ? jQuery.active : 0")
                } catch (e: Exception) {
                    0
                }
                jQueryActive == 0L || jQueryActive == 0
            }
        } catch (e: Exception) {
            logger.debug { "AJAX wait completed or timed out" }
        }
    }

    /**
     * Capture a full page screenshot
     */
    private fun captureFullPageScreenshot(driver: WebDriver): ByteArray? {
        return try {
            if (driver is TakesScreenshot) {
                logger.debug { "Capturing screenshot" }
                driver.getScreenshotAs(OutputType.BYTES)
            } else {
                logger.warn { "WebDriver does not support screenshots" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to capture screenshot" }
            null
        }
    }

    /**
     * Fetch page with retry logic
     */
    fun fetchPageWithRetry(
        url: String,
        captureScreenshot: Boolean = true,
        maxRetries: Int = 3
    ): RawPageData {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return fetchPage(url, captureScreenshot)
            } catch (e: Exception) {
                lastException = e
                logger.warn { "Fetch attempt ${attempt + 1}/$maxRetries failed for $url: ${e.message}" }
                if (attempt < maxRetries - 1) {
                    Thread.sleep(1000L * (attempt + 1)) // Exponential backoff
                }
            }
        }

        throw PageFetchException(
            "Failed to fetch page after $maxRetries attempts: $url",
            lastException
        )
    }
}

class PageFetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
