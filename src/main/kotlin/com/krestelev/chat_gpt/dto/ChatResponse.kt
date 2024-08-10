package com.krestelev.chat_gpt.dto

data class ChatResponse(val choices: List<Choice>) {
    data class Choice(val index: Int, val message: Message)
}
