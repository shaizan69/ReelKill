package com.reelkill.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.reelkill.data.db.entity.DailySummary
import com.reelkill.ui.theme.Accent
import com.reelkill.ui.theme.AccentBg
import com.reelkill.ui.theme.BgSoft
import com.reelkill.ui.theme.BorderHairline
import com.reelkill.ui.theme.Critical
import com.reelkill.ui.theme.Positive
import com.reelkill.ui.theme.TextSecondary
import com.reelkill.ui.theme.TextTertiary
import com.reelkill.ui.theme.Warning
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.dashboardState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Today", "Week", "Insights")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        item { DashboardHeader() }
        item {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = Accent
            ) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) }
                    )
                }
            }
        }
        when (selectedTab) {
            0 -> item { TodayTab(state) }
            1 -> item { WeekTab(state) }
            2 -> item { InsightsTab(state) }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun DashboardHeader() {
    Column {
        Text(
            text = "ReelKill",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Behavior intelligence for short-form feeds",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun TodayTab(state: DashboardState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        EnforcementStatusCard(state)
        ScoreAndBudgetCard(state)
        StatsGrid(state)
        HeatmapCard(state.hourlyHeatmap)
        StreakCard(state)
    }
}

@Composable
private fun WeekTab(state: DashboardState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Eyebrow("THIS WEEK")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.weekAvgScore}",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
            Text("average attention score", color = TextSecondary)
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("Reels", state.weekTotalReels.toString(), Modifier.weight(1f))
                MetricPill("Cooldowns", state.weekTotalCooldowns.toString(), Modifier.weight(1f))
            }
        }
        WeekBars(state.weekSummaries)
        SectionCard {
            Eyebrow("DANGER WINDOW")
            Spacer(modifier = Modifier.height(8.dp))
            val busiestHour = state.hourlyHeatmap.indices.maxByOrNull { state.hourlyHeatmap[it] } ?: 0
            Text(
                text = if (state.hourlyHeatmap.maxOrNull() == 0) "No scrolling data yet" else "$busiestHour:00 - ${(busiestHour + 1) % 24}:00",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("This is where your reel activity clusters most.", color = TextSecondary)
        }
    }
}

@Composable
private fun InsightsTab(state: DashboardState) {
    val budgetProgress = (state.reelsWatchedToday.toFloat() / state.dailyLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val savedEstimateMinutes = ((state.dailyLimit - state.reelsWatchedToday).coerceAtLeast(0) * 2)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Eyebrow("INSIGHT")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    state.hardBlockActive -> "Hard block is doing its job. Do not fight it."
                    state.cooldownActive -> "Cooldown active. Waiting does not consume reels."
                    budgetProgress >= 0.9f -> "You are inside the danger zone. Stop before hard block."
                    budgetProgress >= 0.8f -> "Friction threshold crossed. Move deliberately."
                    else -> "You are in control right now. Keep the budget intact."
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        SectionCard {
            Eyebrow("TIME SAVED")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "~$savedEstimateMinutes min",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Positive
            )
            Text("Estimated time protected against your remaining budget.", color = TextSecondary)
        }
        SectionCard {
            Eyebrow("PATTERNS TRACKED")
            Spacer(modifier = Modifier.height(10.dp))
            InsightLine("Rapid binge", "20+ reels in 15 min")
            InsightLine("Frequent opens", "10+ sessions per day")
            InsightLine("Budget critical", "90% of daily limit")
            InsightLine("Cooldown cluster", "3+ cooldowns in 24h")
            InsightLine("Late night", "11 PM - 5 AM")
        }
    }
}

@Composable
private fun EnforcementStatusCard(state: DashboardState) {
    val statusText = when {
        state.hardBlockActive -> "Hard block active"
        state.cooldownActive -> "Cooldown active"
        else -> "Monitoring active"
    }
    val statusColor = when {
        state.hardBlockActive -> Critical
        state.cooldownActive -> Warning
        else -> Positive
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Eyebrow("STATUS")
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = state.hardBlockExpires ?: state.cooldownExpires ?: "Instagram rules armed",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(statusColor)
            )
        }
    }
}

@Composable
private fun ScoreAndBudgetCard(state: DashboardState) {
    val progress = (state.reelsWatchedToday.toFloat() / state.dailyLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    SectionCard {
        Eyebrow("ATTENTION SCORE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = state.todayAttentionScore.toString(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
            AssistChip(
                onClick = {},
                label = { Text("${state.reelsWatchedToday}/${state.dailyLimit} reels") }
            )
        }
        Text("Score is based on budget, cooldowns, late-night scrolling, and friction.", color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(99.dp)),
            color = if (progress >= 0.9f) Critical else Accent,
            trackColor = BgSoft
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Friction at 80%. Hard ceiling at ${state.dailyLimit}.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
    }
}

@Composable
private fun StatsGrid(state: DashboardState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPill("Sessions", state.todaySessions.toString(), Modifier.weight(1f))
            MetricPill("Cooldowns", state.cooldownsToday.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPill("Time", formatSeconds(state.todayTimeSpentSeconds), Modifier.weight(1f))
            MetricPill("Remaining", (state.dailyLimit - state.reelsWatchedToday).coerceAtLeast(0).toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeatmapCard(hourlyHeatmap: List<Int>) {
    SectionCard {
        Eyebrow("24-HOUR HEATMAP")
        Spacer(modifier = Modifier.height(12.dp))
        val maxValue = hourlyHeatmap.maxOrNull()?.coerceAtLeast(1) ?: 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            hourlyHeatmap.take(24).forEach { value ->
                val height = (8 + (value.toFloat() / maxValue * 54)).roundToInt().dp
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (value > 0) Accent else BgSoft)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("00", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            Text("12", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            Text("23", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

@Composable
private fun StreakCard(state: DashboardState) {
    SectionCard {
        Eyebrow("STREAK")
        Spacer(modifier = Modifier.height(8.dp))
        val streak = state.streak
        Text(
            text = "${streak?.currentStreak ?: 0} day streak",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Best: ${streak?.bestStreak ?: 0}. A streak survives only with zero hard blocks and zero cooldowns.",
            color = TextSecondary
        )
    }
}

@Composable
private fun WeekBars(summaries: List<DailySummary>) {
    SectionCard {
        Eyebrow("7-DAY SCORE")
        Spacer(modifier = Modifier.height(12.dp))
        val maxScore = 100
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val filled = summaries.takeLast(7)
            val padded = List((7 - filled.size).coerceAtLeast(0)) { null } + filled
            padded.forEach { summary ->
                val score = summary?.attentionScore ?: 0
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((12 + score.toFloat() / maxScore * 72).roundToInt().dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (summary?.hardBlockHit == true) Critical else Accent)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = summary?.day?.takeLast(2) ?: "--",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        }
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
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AccentBg, RoundedCornerShape(4.dp))
            .border(1.dp, BorderHairline, RoundedCornerShape(4.dp))
            .padding(12.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InsightLine(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.width(12.dp))
        Text(description, color = TextSecondary)
    }
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

private fun formatSeconds(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes < 60) {
        "${minutes}m"
    } else {
        "${minutes / 60}h ${minutes % 60}m"
    }
}
