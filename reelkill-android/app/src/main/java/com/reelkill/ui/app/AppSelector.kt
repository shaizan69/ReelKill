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
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
        items(AppIds.V1_SUPPORTED_APPS.toList()) { appId ->
            FilterChip(
                selected = appId == selectedAppId,
                onClick = { onAppSelected(appId) },
                label = { Text(AppIds.displayName(appId)) }
            )
        }
    }
}
