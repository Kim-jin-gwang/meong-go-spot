package com.hotdog.meonggocuisine.feature.report.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 오전/오후 토글 + 시 + 분 세 칸으로 시각을 받습니다. 실종·보호 등록 화면이 같이 씁니다.
 *
 * 오류 문구는 세 칸 아래 한 줄로 보입니다. 어느 칸이 틀렸는지는 문구가 말해 준다("시는 1~12…").
 * [onMinuteLeave] 는 분 칸을 떠날 때 부른다 — 시 칸을 떠나는 것은 분을 치러 가는 길이라 오류를 띄우지 않는다.
 */
@Composable
fun EventTimeFields(
    value: EventTimeInput,
    onPeriodChange: (DayPeriod) -> Unit,
    onHourChange: (String) -> Unit,
    onMinuteChange: (String) -> Unit,
    onMinuteLeave: () -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DayPeriodToggle(selected = value.period, onSelect = onPeriodChange, modifier = Modifier.weight(1.4f))
            TimePartField(
                value = value.hour,
                onValueChange = onHourChange,
                unit = "시",
                isError = errorMessage != null,
                modifier = Modifier.weight(1f),
            )
            TimePartField(
                value = value.minute,
                onValueChange = onMinuteChange,
                unit = "분",
                isError = errorMessage != null,
                modifier = Modifier.weight(1f).onLeaveFocus(onMinuteLeave),
            )
        }
        errorMessage?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DayPeriodToggle(
    selected: DayPeriod,
    onSelect: (DayPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            modifier
                .height(OutlinedTextFieldDefaults.MinHeight)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        DayPeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 고른 쪽을 갈색으로 꽉 채우면 시간 한 칸이 화면에서 가장 센 요소가 되어
                        // 정작 눌러야 할 등록 단추보다 눈에 띈다. 선택지 버튼과 같이 옅게 깐다.
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                        )
                        .clickable { onSelect(period) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = period.label,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun TimePartField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val field = rememberFormattedTextFieldState(value)
    OutlinedTextField(
        value = field.value,
        onValueChange = {
            field.value = it
            onValueChange(it.text)
        },
        modifier = modifier,
        placeholder = {
            Text(
                "0",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = PostCreateFieldFontSize),
            )
        },
        suffix = {
            Text(
                text = unit,
                // 숫자와 단위를 조금 띄운다. 붙여 두면 `30분` 이 한 낱말처럼 읽힌다.
                // 위로 2dp 올리는 건 눈으로 맞추는 값이다 — 숫자와 한글은 같은 기준선에 놓아도
                // 글자 모양 때문에 한글이 아래로 처져 보인다.
                modifier = Modifier.padding(start = 8.dp).offset(y = (-2).dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = PostCreateFieldFontSize),
            )
        },
        isError = isError,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        // 숫자를 오른쪽으로 붙인다. 왼쪽에 두면 한두 글자짜리 값이 `시`·`분` 과 멀찍이 떨어져
        // 무엇을 가리키는 숫자인지 한눈에 안 묶인다.
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = PostCreateFieldFontSize, textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
            ),
    )
}

/**
 * 칸에 들어왔다가 나갈 때 [onLeave] 를 부릅니다. 처음 그려질 때의 "포커스 없음" 은 나간 것이 아니므로 무시합니다.
 *
 * 필수 칸의 "입력해 주세요" 는 치는 도중이 아니라 떠난 뒤에 보여야 한다 — 그 시점을 잡는 용도다. [onLeave] 가
 * null 이면 아무것도 붙이지 않는다.
 */
@Composable
fun Modifier.onLeaveFocus(onLeave: (() -> Unit)?): Modifier {
    if (onLeave == null) return this
    var hadFocus by remember { mutableStateOf(false) }
    return onFocusChanged { state ->
        if (state.isFocused) {
            hadFocus = true
        } else if (hadFocus) {
            hadFocus = false
            onLeave()
        }
    }
}
