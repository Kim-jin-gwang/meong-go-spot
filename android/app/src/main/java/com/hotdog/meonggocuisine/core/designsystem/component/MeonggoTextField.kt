package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

@Composable
fun MeonggoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    errorMessage: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    // 여러 줄을 허용하는 곳(채팅 입력창)은 늘어나는 높이에 상한이 있어야 한다.
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
    // 옆에 다른 부품이 나란히 서는 자리(채팅 입력줄)는 같은 모서리를 써야 한 덩어리로 보인다.
    shape: Shape = OutlinedTextFieldDefaults.shape,
) {
    val message = errorMessage ?: supportingText

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodyLarge,
        label = label?.let { { Text(text = it) } },
        placeholder = placeholder?.let { { Text(text = it) } },
        supportingText = message?.let { { Text(text = it) } },
        isError = errorMessage != null,
        singleLine = singleLine,
        maxLines = maxLines,
        shape = shape,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}

@Preview(showBackground = true)
@Composable
private fun MeonggoTextFieldPreview() {
    MeonggoBanjeomTheme {
        Column(verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small)) {
            MeonggoTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = "아이디",
                placeholder = "아이디를 입력하세요",
            )
            MeonggoTextField(
                value = "invalid-id",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = "아이디",
                errorMessage = "아이디를 확인해주세요",
            )
            MeonggoTextField(
                value = "변경할 수 없는 값",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            )
        }
    }
}
