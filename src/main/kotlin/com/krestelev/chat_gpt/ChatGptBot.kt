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
                sendMessage("История запросов очищена", chatId, true)
            }
            "/info" -> sendMessage("Этот бот использует модель OpenAI *${openAiModel.replace("-", "\\-").replace(".", "\\.")}*\\. Чтобы иметь возможность не просто отвечать на вопросы, а вести полноценный диалог, он запоминает историю о 15 предыдущих запросах\\. История запросов очищается каждый день\\. Также, её можно очистить через меню\\. Это полезно, когда ты начинаешь новый диалог или задаешь вопрос по другой теме\\.", chatId, true)
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
                        sendMessage(escapeMarkdownV2(responseMessage), chatId, true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        sendMessage(responseMessage, chatId, false)
                    }
                    userContext[chatId]?.add(Message("assistant", responseMessage))
                }
            }
        }
    }

    fun addMessageToContext(chatId: Long, message: Message) {
        val systemPrompt = """
    You are a helpful assistant generating responses for a Telegram bot. Format all responses in plain text, avoiding any Markdown syntax (e.g., no #, ##, *, **, _, __, ```, etc.) except for the following Telegram-compatible formatting:

    Use _text_ for italic text.
    Use *text* for bold text.
    Use __text__ for underlined text.
    Do not use any other formatting, such as code blocks, lists, headers, or links in Markdown format. If a link is needed, provide it as plain text (e.g., https://example.com). Ensure the response is clear, concise, and suitable for direct use in a Telegram chat.
""".trimIndent()

        if (userContext.containsKey(chatId)) {
            userContext[chatId]?.add(message)
        } else {
            userContext[chatId] = mutableListOf(
                Message("system", systemPrompt),
                message
            )
        }
    }

    fun clearExceedingHistory(chatId: Long) {
        userContext[chatId]?.let { context ->
            if (context.size >= 15) {
                var messagesRemoved = 0
                val index = 1 // Start after system message at index 0
                while (messagesRemoved < 5 && index < context.size) {
                    context.removeAt(index)
                    messagesRemoved++
                }
            }
        }
    }

    fun escapeMarkdownV2(text: String): String {
        val charsToEscape = listOf('[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!')
        return buildString {
            for (char in text) {
                if (char in charsToEscape) append('\\')
                append(char)
            }
        }
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
        val usersLog = userInfos.entries
            .filter { it.value.values.firstOrNull() != 0 }
            .joinToString(", ") {
                (key, value) -> "${value.keys.first()} (chatId - $key) has performed ${value.values.first()} requests\n"
        }
        return "$date: $usersLog"
    }

}