package com.krestelev.chat_gpt

import com.krestelev.chat_gpt.dto.ChatRequest
import com.krestelev.chat_gpt.dto.ChatResponse
import com.krestelev.chat_gpt.dto.Message
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update

@Component
class ChatGptBot(
    @Value("\${bot.token}") botToken: String,
    @Value("\${bot.username}") val botName: String,
    @Value("\${openai.model}") val openAiModel: String,
    @Value("\${openai.api.url}") val openAiApiUrl: String,
    val restTemplate: RestTemplate
) : TelegramLongPollingBot(botToken) {

    override fun getBotUsername(): String = botName

    override fun onUpdateReceived(userInput: Update) {
        val prompt = userInput.message.text
        val message = Message("user", prompt)
        val request = ChatRequest(openAiModel, mutableListOf(message))

        val response: ChatResponse? = restTemplate.postForObject(openAiApiUrl, request, ChatResponse::class.java)

        response?.choices?.let {
            if (it.isNotEmpty()) {
                sendMessage(convertToTelegramMarkdownV2(it.first().message.content), userInput.message.chatId)
            }
        }
    }

    fun convertToTelegramMarkdownV2(input: String): String {
        var result = input

        result = result.replace(Regex("#### (.+)"), "*$1*")  // Convert H4 to bold
        result = result.replace(Regex("### (.+)"), "*$1*")   // Convert H3 to bold
        result = result.replace(Regex("## (.+)"), "*$1*")    // Convert H2 to bold
        result = result.replace(Regex("# (.+)"), "*$1*")     // Convert H1 to bold

        // temporarily replace '**' with a placeholder to avoid conflict when replacing '*'
        result = result.replace("**", "%%BOLD%%")

        // replace remaining '*' (for italic) with '_'
        result = result.replace("*", "_")

        // replace the placeholder '%%BOLD%%' with '*' for bold
        result = result.replace("%%BOLD%%", "*")

        // escape special characters used by Telegram Markdown V2
        val charactersToEscape = listOf('[', ']', '(', ')', '`', '>', '~', '#', '+', '-', '=', '|', '{', '}', '.', '!')
        charactersToEscape.forEach { char ->
            result = result.replace(char.toString(), "\\$char")
        }

        return result
    }

    fun sendMessage(text: String, chatId: Long) {
        val sendMessage = SendMessage()
        sendMessage.chatId = chatId.toString()
        sendMessage.text = text
        sendMessage.enableMarkdownV2(true)
        execute(sendMessage)
    }

}