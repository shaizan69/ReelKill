package com.reelkill.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelkill.common.AppIds

@Composable
fun AppSelector(
    selectedAppId: String,
    onAppSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Hide legacy com.zhiliaoapp.musically from UI - both TikTok packages
    // share the "TikTok" label. Backend still listens to both.
    val uiApps = AppIds.V1_SUPPORTED_APPS
        .filter { it != AppIds.TIKTOK }
        .sortedBy { AppIds.displayName(it) }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
        items(uiApps) { appId ->
            val selected = appId == selectedAppId ||
                (appId == AppIds.TIKTOK_GLOBAL && selectedAppId == AppIds.TIKTOK)
            FilterChip(
                selected = selected,
                onClick = { onAppSelected(appId) },
                label = { Text(AppIds.displayName(appId)) }
            )
        }
    }
}
