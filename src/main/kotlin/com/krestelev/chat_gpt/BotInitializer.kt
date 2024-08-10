package com.krestelev.chat_gpt

import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

@Component
class BotInitializer(val telegramBot: ChatGptBot) {

    @EventListener(ContextRefreshedEvent::class)
    fun init() {
        TelegramBotsApi(DefaultBotSession::class.java).registerBot(telegramBot)
    }
}