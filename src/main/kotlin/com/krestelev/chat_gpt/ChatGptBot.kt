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
    var userInfos: MutableMap<Long, MutableMap<String, Int>> = mutableMapOf()
): TelegramLongPollingBot(botToken) {

    override fun getBotUsername(): String = botName

    override fun onUpdateReceived(userInput: Update) {
        val chatId = userInput.message.chatId
        val prompt = userInput.message.text

        val userInfo = "User ${userInput.message.chat.userName} with name ${userInput.message.chat.firstName}"
        if (userInfos.containsKey(chatId)) {
            val entry = userInfos[chatId]
            entry?.let {
                val currentCount: Int? = it[userInfo]
                currentCount?.let { count ->
                    it[userInfo] = count.inc()
                }
            }
        } else {
            userInfos[chatId] = mutableMapOf(userInfo to 0)
        }

        when (prompt) {
            "/clear" -> {
                userContext[chatId]?.clear()
                sendMessage("Context is cleared \\| Контекст очищен", chatId, true)
            }
            "/get-user-statistics" -> sendMessage(getDailyLog(), chatId, false)
            else -> handleCommon(prompt, chatId)
        }
    }

    fun handleCommon(prompt: String, chatId: Long) {
        val message = Message("user", prompt)
        addMessageToContext(chatId, message)

        clearExceedingHistory(chatId)

        userContext[chatId]?.let { messages ->
            val request = ChatRequest(openAiModel, messages)
            val response: ChatResponse? = restTemplate.postForObject(openAiApiUrl, request, ChatResponse::class.java)
            response?.choices?.let {
                if (it.isNotEmpty()) {
                    val responseMessage = it.first().message.content
                    try {
                        sendMessage(convertToTelegramMarkdownV2(responseMessage), chatId, true)
                    } catch (e: Exception) {
                        sendMessage(responseMessage, chatId, false)
                    }
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

    @Scheduled(cron = "1 0 0 * * *")
    fun clearOldHistory() {
        userContext.values.forEach { it.clear() }
        saveUserInfo()
        userInfos = mutableMapOf()
    }

    fun saveUserInfo() {
        val dailyLog = getDailyLog()
        if (dailyLog.isNotEmpty()) {
            FileUtils.writeStringToFile(ResourceUtils.getFile("./users.txt"), dailyLog,
                StandardCharsets.UTF_8, true)
        }
    }

    private fun getDailyLog(): String {
        val date = LocalDate.now(ZoneId.of("Europe/Moscow")).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val usersLog = userInfos.entries.joinToString(", ") {
                (key, value) -> "${value.keys.first()} (chatId - $key) has performed ${value.values.first()} requests\n"
        }
        return "$date: $usersLog"
    }

}