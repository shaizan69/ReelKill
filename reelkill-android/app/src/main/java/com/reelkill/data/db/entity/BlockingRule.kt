package com.reelkill.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocking_rules",
    indices = [
        Index(value = ["appPackage", "isActive"]),
        Index(value = ["viewId"]),
        Index(value = ["contentDescContains"])
    ]
)
data class BlockingRule(
    @PrimaryKey val id: String,
    val appPackage: String,
    val viewId: String?,
    val contentDescContains: String?,
    val action: String,
    val isActive: Boolean,
    val addedAt: String
) {
    init {
        require(viewId != null || contentDescContains != null) {
            "Either viewId or contentDescContains must be provided"
        }
        require(action in SUPPORTED_ACTIONS) { "Unsupported blocking rule action: $action" }
    }

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        const val TIKTOK_GLOBAL_PACKAGE = "com.ss.android.ugc.trill"
        const val FACEBOOK_PACKAGE = "com.facebook.katana"
        const val SNAPCHAT_PACKAGE = "com.snapchat.android"
        const val REDDIT_PACKAGE = "com.reddit.frontpage"
        const val LINKEDIN_PACKAGE = "com.linkedin.android"

        val ALL_BLOCKED_PACKAGES = setOf(
            INSTAGRAM_PACKAGE,
            YOUTUBE_PACKAGE,
            TIKTOK_PACKAGE,
            TIKTOK_GLOBAL_PACKAGE,
            FACEBOOK_PACKAGE,
            SNAPCHAT_PACKAGE,
            REDDIT_PACKAGE,
            LINKEDIN_PACKAGE
        )

        const val ACTION_BACK = "BACK"
        const val ACTION_HIDE = "HIDE"
        const val ACTION_LOG = "LOG"
        const val ACTION_ALLOW = "ALLOW"

        val SUPPORTED_ACTIONS = setOf(
            ACTION_BACK,
            ACTION_HIDE,
            ACTION_LOG,
            ACTION_ALLOW
        )
    }
}
