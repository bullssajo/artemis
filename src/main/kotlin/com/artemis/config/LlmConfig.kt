package com.artemis.config

import okhttp3.OkHttpClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    val provider: String = "openai",
    val apiKey: String = "",
    val model: String = "gpt-4o",
    val baseUrl: String = "https://api.openai.com/v1",
    val timeoutSeconds: Long = 120
)

@Configuration
class LlmConfig {

    @Bean
    fun llmProperties(): LlmProperties = LlmProperties()

    @Bean
    fun okHttpClient(properties: LlmProperties): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(properties.timeoutSeconds))
            .writeTimeout(Duration.ofSeconds(60))
            .build()
    }
}
