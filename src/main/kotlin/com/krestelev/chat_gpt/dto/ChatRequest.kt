package com.krestelev.chat_gpt.dto

data class ChatRequest(
    val model: String,
    val messages: MutableList<Message>,
    var n: Int = 1,
    var temperature: Double = 1.0
)