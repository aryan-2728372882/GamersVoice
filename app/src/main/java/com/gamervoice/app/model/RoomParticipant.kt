package com.gamervoice.app.model

data class RoomParticipant(
    val peerId: String,
    val name: String,
    val avatar: String,
    val isMe: Boolean = false,
    var isSpeaking: Boolean = false
)
