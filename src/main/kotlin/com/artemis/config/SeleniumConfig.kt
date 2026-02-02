package com.artemis.config

import mu.KotlinLogging
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.remote.RemoteWebDriver
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URL
import java.time.Duration

private val logger = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "selenium.chrome")
data class SeleniumProperties(
    val driverPath: String = "",
    val remoteUrl: String = "http://localhost:4444/wd/hub",
    val headless: Boolean = true,
    val timeoutSeconds: Long = 30,
    val pageLoadTimeoutSeconds: Long = 60
)

@Configuration
class SeleniumConfig {

    @Bean
    fun seleniumProperties(): SeleniumProperties = SeleniumProperties()

    @Bean
    fun chromeOptions(properties: SeleniumProperties): ChromeOptions {
        return ChromeOptions().apply {
            if (properties.headless) {
                addArguments("--headless=new")
            }
            addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--disable-extensions",
                "--disable-popup-blocking",
                "--window-size=1920,1080",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            // For better content rendering
            addArguments("--lang=en-US,en")
            setExperimentalOption("excludeSwitches", listOf("enable-automation"))
        }
    }
}

/**
 * Factory for creating WebDriver instances
 */
@Configuration
class WebDriverFactory(
    private val properties: SeleniumProperties,
    private val chromeOptions: ChromeOptions
) {

    fun createDriver(): WebDriver {
        return try {
            if (properties.remoteUrl.isNotBlank()) {
                logger.info { "Connecting to remote Selenium server: ${properties.remoteUrl}" }
                createRemoteDriver()
            } else {
                logger.info { "Creating local Chrome driver" }
                createLocalDriver()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create WebDriver, falling back to local driver" }
            createLocalDriver()
        }
    }

    private fun createRemoteDriver(): WebDriver {
        return RemoteWebDriver(URL(properties.remoteUrl), chromeOptions).apply {
            manage().timeouts().apply {
                implicitlyWait(Duration.ofSeconds(properties.timeoutSeconds))
                pageLoadTimeout(Duration.ofSeconds(properties.pageLoadTimeoutSeconds))
                scriptTimeout(Duration.ofSeconds(properties.timeoutSeconds))
            }
        }
    }

    private fun createLocalDriver(): WebDriver {
        if (properties.driverPath.isNotBlank()) {
            System.setProperty("webdriver.chrome.driver", properties.driverPath)
        }
        return ChromeDriver(chromeOptions).apply {
            manage().timeouts().apply {
                implicitlyWait(Duration.ofSeconds(properties.timeoutSeconds))
                pageLoadTimeout(Duration.ofSeconds(properties.pageLoadTimeoutSeconds))
                scriptTimeout(Duration.ofSeconds(properties.timeoutSeconds))
            }
        }
    }
}
