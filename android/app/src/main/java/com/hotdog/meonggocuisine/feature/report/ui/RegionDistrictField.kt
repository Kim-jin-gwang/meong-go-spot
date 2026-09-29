package com.hotdog.meonggocuisine.feature.report.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.component.DropdownChevron
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoDropdownMenu
import com.hotdog.meonggocuisine.feature.community.ui.RegionCatalog
import com.hotdog.meonggocuisine.feature.community.ui.RegionDistrict

/**
 * 게시물 등록용 시·군·구 선택 필드. 게시물 지역은 5자리 시·군·구 코드가 필수라
 * 커뮤니티 목록의 "전국"·"시·도 전체" 없이 [RegionCatalog] 만 보여 준다
 * (docs/post-date-location-policy.md §4.1).
 */
@Composable
fun RegionDistrictField(
    selectedRegionCode: String?,
    onRegionSelected: (code: String?, name: String?) -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    val selected = remember(selectedRegionCode) { findDistrict(selectedRegionCode) }
    var province by rememberSaveable { mutableStateOf(selected?.first) }
    // 복원된 시·도와 실제 선택 코드가 어긋나면(프로세스 재생성 등) 코드 쪽에 맞춘다.
    // 어긋난 채 두면 화면과 다른 지역이 조용히 전송된다.
    LaunchedEffect(selected) {
        if (selected != null) {
            province = selected.first
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RegionDropdownField(
                text = province,
                placeholder = "시·도",
                options = RegionCatalog.districts.keys.toList(),
                optionLabel = { it },
                onSelected = { newProvince ->
                    if (newProvince != province) {
                        province = newProvince
                        // 시·도를 바꾸면 이전 시·군·구 선택은 무효다 — 남겨 두면 화면과 다른 지역이 조용히 전송된다.
                        if (selected != null) onRegionSelected(null, null)
                    }
                },
                isError = errorMessage != null,
                modifier = Modifier.weight(1f),
            )
            val currentProvince = province
            RegionDropdownField(
                text = selected?.takeIf { it.first == currentProvince }?.second?.name,
                placeholder = "시·군·구",
                options = currentProvince?.let { RegionCatalog.districts[it] }.orEmpty(),
                optionLabel = { it.name },
                onSelected = { district ->
                    if (currentProvince != null) {
                        onRegionSelected(district.code, districtDisplayName(currentProvince, district))
                    }
                },
                isError = errorMessage != null,
                modifier = Modifier.weight(1f),
            )
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
}

@Composable
private fun <T> RegionDropdownField(
    text: String?,
    placeholder: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            // 높이는 글자 칸과 같은 값을 쓴다. 48dp 로 따로 두었더니 같은 줄에 선 장소 칸보다
            // 낮아, 지역만 눌러 앉은 것처럼 보였다.
            modifier = Modifier.fillMaxWidth().height(OutlinedTextFieldDefaults.MinHeight),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            border =
                BorderStroke(
                    1.dp,
                    if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                ),
            // 면은 비워 둔다 — 옆의 입력칸(OutlinedTextField)처럼 얹힌 카드 색이 그대로 비쳐야 한 줄로 보인다.
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
        ) {
            Text(
                text = text ?: placeholder,
                modifier = Modifier.weight(1f),
                color =
                    if (text == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = PostCreateFieldFontSize),
            )
            DropdownChevron()
        }
        MeonggoDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = options,
            selected = options.firstOrNull { optionLabel(it) == text },
            optionLabel = optionLabel,
            onSelected = {
                expanded = false
                onSelected(it)
            },
        )
    }
}

/** 시·군·구 코드를 [RegionCatalog] 의 (시·도, 항목) 으로 되돌린다. 모르는 코드는 null. */
private fun findDistrict(code: String?): Pair<String, RegionDistrict>? =
    code?.let {
        RegionCatalog.districts.entries.firstNotNullOfOrNull { (province, districts) ->
            districts.firstOrNull { district -> district.code == code }?.let { district -> province to district }
        }
    }

private fun districtDisplayName(
    province: String,
    district: RegionDistrict,
): String = district.display ?: "$province ${district.name}"
