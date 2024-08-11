package com.krestelev.chat_gpt

import com.krestelev.chat_gpt.dto.ChatRequest
import com.krestelev.chat_gpt.dto.ChatResponse
import com.krestelev.chat_gpt.dto.Message
import org.apache.commons.io.FileUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.util.ResourceUtils
import org.springframework.web.client.RestTemplate
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
@EnableScheduling
class ChatGptBot(
    @Value("\${bot.token}") botToken: String,
    @Value("\${bot.username}") val botName: String,
    @Value("\${openai.model}") val openAiModel: String,
    @Value("\${openai.api.url}") val openAiApiUrl: String,
    val restTemplate: RestTemplate,
    val userContext: MutableMap<Long, MutableList<Message>> = mutableMapOf(),
    var userInfo: MutableMap<Long, MutableMap<String, Int>> = mutableMapOf()
): TelegramLongPollingBot(botToken) {

    override fun getBotUsername(): String = botName

    override fun onUpdateReceived(userInput: Update) {
        val chatId = userInput.message.chatId
        val prompt = userInput.message.text

        if (userInfo.containsKey(chatId)) {
            val entry = userInfo[chatId]
            entry?.let {
                val currentCount: Int? = it[userInput.message.chat.userName]
                currentCount?.let { count ->
                    it[userInput.message.chat.userName] = count.inc()
                }
            }
        } else {
            userInfo[chatId] = mutableMapOf(userInput.message.chat.userName to 0)
        }

        if (prompt == "/clear") {
             userContext[chatId]?.clear()
             sendMessage("Context is cleared \\| Контекст очищен", chatId, true)
        } else {
            handlePrompt(prompt, chatId)
        }
    }

    fun handlePrompt(prompt: String, chatId: Long) {
        val message = Message("user", prompt)
        addMessageToContext(chatId, message)

        clearExceedingHistory(chatId)

        userContext[chatId]?.let { messages ->
            val request = ChatRequest(openAiModel, messages)
            val response: ChatResponse? = restTemplate.postForObject(openAiApiUrl, request, ChatResponse::class.java)
            response?.choices?.let {
                if (it.isNotEmpty()) {
                    val responseMessage = it.first().message.content
                    sendMessage(convertToTelegramMarkdownV2(responseMessage), chatId, true)
                    userContext[chatId]?.add(Message("assistant", responseMessage))
                }
            }
        }
    }

    fun addMessageToContext(chatId: Long, message: Message) {
        if (userContext.containsKey(chatId)) {
            userContext[chatId]?.add(message)
        } else {
            userContext[chatId] = mutableListOf(message)
        }
    }

    fun clearExceedingHistory(chatId: Long) {
        userContext[chatId]?.let {
            if (it.size >= 15) {
                for (i in 1..5) {
                    userContext[chatId]?.removeAt(0)
                }

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

    fun sendMessage(text: String, chatId: Long, enableMarkdown: Boolean) {
        val sendMessage = SendMessage()
        sendMessage.chatId = chatId.toString()
        sendMessage.text = text
        sendMessage.enableMarkdownV2(enableMarkdown)
        execute(sendMessage)
    }

    @Scheduled(cron = "*/15 * * * * *")
    fun clearOldHistory() {
        userContext.values.forEach { it.clear() }
        saveUserInfo()
        userInfo = mutableMapOf()
    }

    fun saveUserInfo() {
        val date = LocalDate.now(ZoneId.of("Europe/Moscow")).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val users = userInfo.entries.joinToString(", ") { (key, value) -> "$key - ${value.keys.first()} (${value.values.first()})\n" }
        val dayLog = "$date: $users"
        if (users.isNotEmpty()) {
            sendMessage(dayLog, 531814574, false)
            FileUtils.writeStringToFile(ResourceUtils.getFile("./users.txt"), dayLog,
                StandardCharsets.UTF_8, true)
        }

    }

}