package com.krestelev.chat_gpt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ChatGptBotApplication

fun main(args: Array<String>) {
	runApplication<ChatGptBotApplication>(*args)
}
