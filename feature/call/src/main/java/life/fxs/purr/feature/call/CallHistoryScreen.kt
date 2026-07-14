package life.fxs.purr.feature.call

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle

@Composable
fun CallHistoryScreenRoute(
    onOpenDay: (LocalDate) -> Unit,
    viewModel: CallHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CallHistoryScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onOpenDay = onOpenDay,
    )
}

@Composable
fun CallHistoryScreen(
    state: CallHistoryState,
    onIntent: (CallHistoryIntent) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    var showMonthPicker by rememberSaveable { mutableStateOf(false) }
    var pickerYear by rememberSaveable { mutableIntStateOf(state.displayedMonth.year) }
    LaunchedEffect(state.displayedMonth) { pickerYear = state.displayedMonth.year }

    PurrScreen {
        PurrSectionTitle(eyebrow = "通话", title = "通话历史", subtitle = "")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onIntent(CallHistoryIntent.PreviousMonth) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
            }
            Surface(
                modifier = Modifier.clickable {
                    showMonthPicker = !showMonthPicker
                },
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Text(
                        text = "${state.displayedMonth.year}年 ${state.displayedMonth.monthValue}月",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            IconButton(onClick = { onIntent(CallHistoryIntent.NextMonth) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
            }
        }

        AnimatedVisibility(
            visible = showMonthPicker,
            enter = fadeIn(tween(MOTION_DURATION)) + slideInHorizontally { it / 5 },
            exit = fadeOut(tween(MOTION_DURATION)) + slideOutHorizontally { it / 5 },
        ) {
            MonthPicker(
                year = pickerYear,
                selected = state.displayedMonth,
                onPreviousYear = { pickerYear-- },
                onNextYear = { pickerYear++ },
                onSelect = { month ->
                    showMonthPicker = false
                    onIntent(CallHistoryIntent.SelectMonth(month))
                },
            )
        }

        AnimatedContent(
            targetState = state.displayedMonth,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(MOTION_DURATION, easing = FastOutSlowInEasing)) {
                    direction * it / 3
                } + fadeIn(tween(MOTION_DURATION))) togetherWith
                    (slideOutHorizontally(tween(MOTION_DURATION, easing = FastOutSlowInEasing)) {
                        -direction * it / 3
                    } + fadeOut(tween(MOTION_DURATION))) using SizeTransform(clip = false)
            },
            label = "call-calendar-month",
        ) { month ->
            MonthCalendar(
                month = month,
                callDates = state.days,
                onOpenDay = onOpenDay,
            )
        }

        if (state.isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }

        state.errorMessage?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { onIntent(CallHistoryIntent.Retry) }) { Text("重试") }
            }
        }

        if (!state.isLoading && state.errorMessage == null && state.days.isEmpty()) {
            Text(
                text = "这个月还没有通话记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun MonthPicker(
    year: Int,
    selected: YearMonth,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
    onSelect: (YearMonth) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPreviousYear) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一年")
            }
            Text("${year}年", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onNextYear) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一年")
            }
        }
        Month.entries.chunked(3).forEach { rowMonths ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowMonths.forEach { month ->
                    val yearMonth = YearMonth.of(year, month)
                    val selectedMonth = yearMonth == selected
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(yearMonth) },
                        shape = MaterialTheme.shapes.small,
                        color = if (selectedMonth) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                    ) {
                        Text(
                            text = "${month.value}月",
                            modifier = Modifier.padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (selectedMonth) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    callDates: Map<LocalDate, life.fxs.purr.domain.call.model.CallCalendarDay>,
    onOpenDay: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEK_LABELS.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        month.calendarCells().chunked(DAYS_IN_WEEK).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    CalendarDay(
                        date = date,
                        callCount = date?.let(callDates::get)?.callCount ?: 0,
                        onClick = { date?.let(onOpenDay) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate?,
    callCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCalls = callCount > 0
    val isToday = date == LocalDate.now()
    Box(
        modifier = modifier.aspectRatio(1f).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (date == null) return@Box
        Surface(
            modifier = Modifier
                .size(42.dp)
                .then(
                    if (hasCalls) {
                        Modifier
                            .semantics {
                                role = Role.Button
                                contentDescription = "${date.monthValue}月${date.dayOfMonth}日，${callCount}次通话"
                            }
                            .clickable(onClick = onClick)
                    } else {
                        Modifier.alpha(0.72f)
                    },
                ),
            shape = CircleShape,
            color = if (hasCalls) MaterialTheme.colorScheme.primary else Color.Transparent,
            border = if (isToday && !hasCalls) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = date.dayOfMonth.toString(),
                    color = if (hasCalls) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (hasCalls || isToday) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun YearMonth.calendarCells(): List<LocalDate?> {
    val first = atDay(1)
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + DAYS_IN_WEEK) % DAYS_IN_WEEK
    return List(CALENDAR_CELL_COUNT) { index ->
        val day = index - leading + 1
        day.takeIf { it in 1..lengthOfMonth() }?.let(::atDay)
    }
}

private const val DAYS_IN_WEEK = 7
private const val CALENDAR_CELL_COUNT = 42
private const val MOTION_DURATION = 280
private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
