package com.krestelev.chat_gpt

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate

@Configuration
class OpenAiRestTemplateConfig(@Value("\${openai.api.key}") val openaiApiKey: String) {

    @Bean
    fun openAiRestTemplate(): RestTemplate {
        val restTemplate: RestTemplate = RestTemplate()
        restTemplate.interceptors.add { request, body, execution ->
            request.headers.add("Authorization", "Bearer $openaiApiKey")
            execution.execute(request, body)
        }
        return restTemplate
    }
}