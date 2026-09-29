package com.hotdog.meonggocuisine.feature.report.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * 사건 날짜를 달력에서 고르는 칸. 실종·보호 등록과 게시물 수정이 같이 쓴다.
 *
 * 숫자 8자리(`20260909`)를 치게 했더니 QA(2026-09-21)에서 달력이 낫겠다는 말이 나왔다. 값은 여전히 `yyyy-MM-dd` 문자열이라
 * 뷰모델·검증기·서버 요청은 그대로다. 미래 날짜는 달력에서 고를 수 없게 막는다(검증기의 "내일 이후" 규칙과 같음).
 *
 * `DatePicker` 는 UTC 자정의 epoch millis 로 날짜를 주고받는다 — 기기 시간대와 섞으면 하루가 밀리므로 UTC 로만 변환한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "달력에서 날짜 선택",
    errorMessage: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val isError = errorMessage != null
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().height(OutlinedTextFieldDefaults.MinHeight),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            border =
                BorderStroke(
                    1.dp,
                    if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                ),
            // 면은 비워 둔다 — 옆의 입력칸(OutlinedTextField)처럼 얹힌 카드 색이 그대로 비쳐야 한 줄로 보인다.
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                modifier = Modifier.weight(1f),
                color =
                    if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = PostCreateFieldFontSize),
            )
            CalendarGlyph()
        }
        errorMessage?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (open) {
        val today = LocalDate.now()
        val initial = parseDate(value) ?: today
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis = initial.toUtcMillis(),
                selectableDates =
                    remember(today) {
                        object : SelectableDates {
                            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= today.toUtcMillis()

                            override fun isSelectableYear(year: Int): Boolean = year <= today.year
                        }
                    },
            )
        // Material 기본 다이얼로그 면은 연보라(surfaceContainerHigh)라 앱의 흰 면으로 맞춘다 (QA 1번과 같은 지적).
        val pickerColors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onValueChange(fromUtcMillis(it).toString()) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) {
                    Text("선택")
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("취소") } },
            colors = pickerColors,
        ) {
            DatePicker(state = state, showModeToggle = false, colors = pickerColors)
        }
    }
}

private fun parseDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun fromUtcMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** 달력 모양 — 앱에 아이콘 라이브러리를 들이지 않고 Canvas 로 그린다(다른 아이콘과 같은 방식). */
@Composable
private fun CalendarGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(18.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx())
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.2f),
            size = Size(w * 0.76f, h * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = stroke,
        )
        drawLine(color, Offset(w * 0.12f, h * 0.4f), Offset(w * 0.88f, h * 0.4f), strokeWidth = stroke.width)
        drawLine(color, Offset(w * 0.34f, h * 0.1f), Offset(w * 0.34f, h * 0.28f), strokeWidth = stroke.width)
        drawLine(color, Offset(w * 0.66f, h * 0.1f), Offset(w * 0.66f, h * 0.28f), strokeWidth = stroke.width)
        drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.34f, h * 0.6f))
        drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.5f, h * 0.6f))
        drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.66f, h * 0.6f))
    }
}
