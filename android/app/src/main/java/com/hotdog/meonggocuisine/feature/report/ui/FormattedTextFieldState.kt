package com.hotdog.meonggocuisine.feature.report.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 입력한 글자와 화면에 남는 글자가 다를 수 있는 칸의 커서를 잡아 줍니다.
 *
 * 날짜·시간 칸은 숫자를 치면 구분선이 끼어들어 글자 수가 늘어납니다. `String` 을 받는
 * `OutlinedTextField` 는 커서 위치를 글자 수로만 기억해서, 늘어난 만큼 커서가 뒤처지고
 * 다음 숫자가 엉뚱한 자리에 끼어듭니다. `20260815` 를 치면 `20268150` 이 되던 이유입니다.
 *
 * 되돌아온 값이 방금 친 글자와 다르면 커서를 끝으로 보냅니다. 값을 그대로 돌려주는 보통 칸은
 * 두 값이 같으므로 커서를 건드리지 않아 중간 편집이 그대로 됩니다.
 */
@Composable
internal fun rememberFormattedTextFieldState(value: String): MutableState<TextFieldValue> {
    val state = remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (state.value.text != value) {
        state.value = TextFieldValue(value, TextRange(value.length))
    }
    return state
}
