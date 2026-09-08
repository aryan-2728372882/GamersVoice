package com.gamervoice.app.util

import com.gamervoice.app.R

object AvatarHelper {

    val AVATAR_KEYS = listOf("avatar_1", "avatar_2", "avatar_3", "avatar_4", "avatar_5")

    fun getDrawableRes(avatarKey: String?): Int {
        return when (avatarKey) {
            "avatar_2" -> R.drawable.ic_avatar_2
            "avatar_3" -> R.drawable.ic_avatar_3
            "avatar_4" -> R.drawable.ic_avatar_4
            "avatar_5" -> R.drawable.ic_avatar_5
            else -> R.drawable.ic_avatar_1
        }
    }
}
