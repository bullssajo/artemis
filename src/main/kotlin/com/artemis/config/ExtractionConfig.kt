package com.artemis.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "extraction")
data class ExtractionProperties(
    val minTextLength: Int = 50,
    val maxContentLength: Int = 50000,
    val screenshot: ScreenshotProperties = ScreenshotProperties()
)

data class ScreenshotProperties(
    val enabled: Boolean = true,
    val width: Int = 1920,
    val height: Int = 1080
)

@Configuration
@EnableConfigurationProperties(
    SeleniumProperties::class,
    LlmProperties::class,
    ExtractionProperties::class
)
class ExtractionConfig
