package com.reelkill.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.ui.theme.Accent
import com.reelkill.ui.theme.AccentBg
import com.reelkill.ui.theme.BorderHairline
import com.reelkill.ui.theme.TextSecondary
import com.reelkill.ui.theme.TextTertiary

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val message by viewModel.lastWriteMessage.collectAsState()
    var pin by remember { mutableStateOf(settings.strictModePin.orEmpty()) }

    LaunchedEffect(settings.strictModePin) {
        pin = settings.strictModePin.orEmpty()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        item {
            Text(
                text = "Rules",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "ScrollGuard controls plus ReelKill enforcement.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        if (message != null) {
            item {
                SectionCard {
                    Text(message.orEmpty(), color = Accent, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = viewModel::clearMessage) { Text("Dismiss") }
                }
            }
        }

        item {
            SectionCard {
                Eyebrow("SCROLLGUARD BLOCKING")
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitch(
                    title = "Block Reels tab",
                    description = "Back out when Instagram opens the Reels surface.",
                    checked = settings.blockReelsTab,
                    onCheckedChange = viewModel::toggleBlockReelsTab
                )
                SettingSwitch(
                    title = "Block Explore",
                    description = "Stops the Explore rabbit hole before it starts.",
                    checked = settings.blockExplore,
                    onCheckedChange = viewModel::toggleBlockExplore
                )
                SettingSwitch(
                    title = "Hide Stories tray",
                    description = "Accessibility rule hides story entry points where possible.",
                    checked = settings.blockStories,
                    onCheckedChange = viewModel::toggleBlockStories
                )
                SettingSwitch(
                    title = "Hide Suggested posts",
                    description = "Keeps the main feed from becoming infinite recommendations.",
                    checked = settings.blockSuggested,
                    onCheckedChange = viewModel::toggleBlockSuggested
                )
                SettingSwitch(
                    title = "Allow Reels in DMs",
                    description = "Loosens enforcement for shared reels in messages.",
                    checked = settings.allowReelsInDm,
                    onCheckedChange = viewModel::toggleAllowReelsInDm
                )
            }
        }

        item {
            SectionCard {
                Eyebrow("DAILY BUDGET")
                Spacer(modifier = Modifier.height(10.dp))
                SettingSlider(
                    title = "Daily reel limit",
                    valueLabel = "${settings.dailyLimit} reels",
                    value = settings.dailyLimit.toFloat(),
                    valueRange = 1f..50f,
                    steps = 48,
                    onValueChangeFinished = { viewModel.updateDailyLimit(it.toInt()) }
                )
                Text(
                    text = "Hard ceiling is 50. Higher limits are never allowed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                Spacer(modifier = Modifier.height(16.dp))
                SettingSlider(
                    title = "Friction warning threshold",
                    valueLabel = "${(settings.frictionThresholdPct * 100).toInt()}%",
                    value = settings.frictionThresholdPct,
                    valueRange = 0.5f..1f,
                    steps = 9,
                    onValueChangeFinished = viewModel::updateFrictionThreshold
                )
            }
        }

        item {
            SectionCard {
                Eyebrow("BINGE + COOLDOWN")
                Spacer(modifier = Modifier.height(10.dp))
                SettingSlider(
                    title = "Binge threshold",
                    valueLabel = "${settings.bingeThresholdReels} reels",
                    value = settings.bingeThresholdReels.toFloat(),
                    valueRange = 3f..50f,
                    steps = 46,
                    onValueChangeFinished = { viewModel.updateBingeThreshold(it.toInt()) }
                )
                SettingSlider(
                    title = "Binge window",
                    valueLabel = "${settings.bingeWindowMinutes} min",
                    value = settings.bingeWindowMinutes.toFloat(),
                    valueRange = 3f..60f,
                    steps = 56,
                    onValueChangeFinished = { viewModel.updateBingeWindow(it.toInt()) }
                )
                SettingSlider(
                    title = "Base cooldown",
                    valueLabel = "${settings.cooldownBaseSeconds / 60} min",
                    value = (settings.cooldownBaseSeconds / 60).toFloat(),
                    valueRange = 1f..60f,
                    steps = 58,
                    onValueChangeFinished = { viewModel.updateCooldownBase(it.toInt() * 60) }
                )
                Text(
                    text = "Escalation: 1x, 2x, 4x, then 6x within a rolling 24-hour cooldown window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        item {
            SectionCard {
                Eyebrow("ANTI-SCROLL")
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitch(
                    title = "Doomscroll detection",
                    description = "Counts rapid swipe-up bursts and prompts you to stop.",
                    checked = settings.antiScrollEnabled,
                    onCheckedChange = viewModel::toggleAntiScroll
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SensitivityChip(
                        label = "Strict",
                        selected = settings.antiScrollSensitivity == AppSettings.SENSITIVITY_STRICT,
                        onClick = { viewModel.updateAntiScrollSensitivity(AppSettings.SENSITIVITY_STRICT) }
                    )
                    SensitivityChip(
                        label = "Chill",
                        selected = settings.antiScrollSensitivity == AppSettings.SENSITIVITY_CHILL,
                        onClick = { viewModel.updateAntiScrollSensitivity(AppSettings.SENSITIVITY_CHILL) }
                    )
                    SensitivityChip(
                        label = "Custom",
                        selected = settings.antiScrollSensitivity == AppSettings.SENSITIVITY_CUSTOM,
                        onClick = { viewModel.updateAntiScrollSensitivity(AppSettings.SENSITIVITY_CUSTOM) }
                    )
                }
            }
        }

        item {
            SectionCard {
                Eyebrow("STRICT MODE")
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitch(
                    title = "Require PIN for changes",
                    description = "Use this with an accountability partner.",
                    checked = settings.strictModeEnabled,
                    onCheckedChange = viewModel::toggleStrictMode
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text("Strict Mode PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.updateStrictModePin(pin) },
                    enabled = pin.length >= 4
                ) {
                    Text("Save PIN")
                }
                SettingSwitch(
                    title = "Accountability partner",
                    description = "Partner holds the PIN so changes require external friction.",
                    checked = settings.accountabilityPartnerEnabled,
                    onCheckedChange = viewModel::toggleAccountabilityPartner
                )
            }
        }

        item {
            SectionCard {
                Eyebrow("SCHEDULED BREAKS")
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = settings.scheduledBreakStart?.let { "Active: $it - ${settings.scheduledBreakEnd}" } ?: "No scheduled break set",
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { viewModel.updateScheduledBreak("19:00", "21:00") },
                        label = { Text("7-9 PM") }
                    )
                    AssistChip(
                        onClick = { viewModel.updateScheduledBreak("23:00", "05:00") },
                        label = { Text("Sleep") }
                    )
                    AssistChip(
                        onClick = { viewModel.updateScheduledBreak(null, null) },
                        label = { Text("Clear") }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionCard(content: @Composable Column.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
            .border(1.dp, BorderHairline, RoundedCornerShape(4.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit
) {
    var localValue by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(valueLabel, color = Accent)
        }
        Slider(
            value = localValue,
            onValueChange = { localValue = it },
            onValueChangeFinished = { onValueChangeFinished(localValue) },
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun SensitivityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = TextTertiary
    )
}
