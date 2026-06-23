package com.reelkill.common

object AppIds {
    const val INSTAGRAM = "com.instagram.android"
    const val YOUTUBE = "com.google.android.youtube"
    const val TIKTOK = "com.zhiliaoapp.musically"
    const val FACEBOOK = "com.facebook.katana"
    const val SNAPCHAT = "com.snapchat.android"
    const val REDDIT = "com.reddit.frontpage"
    const val LINKEDIN = "com.linkedin.android"

    val V1_SUPPORTED_APPS = setOf(
        INSTAGRAM,
        YOUTUBE,
        TIKTOK,
        FACEBOOK,
        SNAPCHAT,
        REDDIT,
        LINKEDIN
    )

    fun displayName(packageName: String): String {
        return when (packageName) {
            INSTAGRAM -> "Instagram"
            YOUTUBE -> "YouTube"
            TIKTOK -> "TikTok"
            FACEBOOK -> "Facebook"
            SNAPCHAT -> "Snapchat"
            REDDIT -> "Reddit"
            LINKEDIN -> "LinkedIn"
            else -> packageName
        }
    }

    fun isSupported(packageName: String): Boolean {
        return packageName in V1_SUPPORTED_APPS
    }
}