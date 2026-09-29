package com.hotdog.meonggocuisine.feature.community.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.DropdownChevron
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoDropdownMenu
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

@Composable
fun RegionSelectionRouteScreen(
    onSelectionComplete: () -> Unit,
    onLostTabClick: () -> Unit,
    onHomeTabClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegionSelectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RegionSelectionScreen(
        uiState = uiState,
        onProvinceSelected = viewModel::selectProvince,
        onDistrictSelected = viewModel::selectDistrict,
        onSelectionComplete = {
            viewModel.saveSelection()
            onSelectionComplete()
        },
        onLostTabClick = onLostTabClick,
        onHomeTabClick = onHomeTabClick,
        modifier = modifier,
    )
}

@Composable
fun RegionSelectionScreen(
    uiState: RegionSelectionUiState,
    onProvinceSelected: (String) -> Unit,
    onDistrictSelected: (RegionDistrict) -> Unit,
    onSelectionComplete: () -> Unit,
    onLostTabClick: () -> Unit,
    modifier: Modifier = Modifier,
    onHomeTabClick: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        BrandHeader()
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RegionIllustration()
            Spacer(Modifier.height(42.dp))
            Text(
                "관심 지역을 선택해주세요",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "선택한 지역의 보호동물을 보여드려요.",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 42.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RegionDropdown(
                    value = uiState.selectedProvince,
                    values = RegionOptions.provinces,
                    onSelected = onProvinceSelected,
                    modifier = Modifier.weight(1f),
                )
                DistrictDropdown(
                    value = uiState.selectedDistrict,
                    values = uiState.districts,
                    onSelected = onDistrictSelected,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = onSelectionComplete,
                modifier = Modifier.fillMaxWidth().padding(top = 36.dp).height(54.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text("선택 완료", fontWeight = FontWeight.Bold)
            }
        }
        CommunityBottomBar(
            selectedTab = CommunityTabType.SHELTERING,
            onLostTabClick = onLostTabClick,
            onShelteringTabClick = {},
            onHomeTabClick = onHomeTabClick,
        )
    }
}

@Composable
private fun RegionDropdown(
    value: String,
    values: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(Color.White, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
        ) {
            Text(value, Modifier.weight(1f), maxLines = 1)
            DropdownChevron()
        }
        MeonggoDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = values,
            selected = value,
            optionLabel = { it },
            onSelected = {
                expanded = false
                onSelected(it)
            },
        )
    }
}

@Composable
private fun DistrictDropdown(
    value: RegionDistrict,
    values: List<RegionDistrict>,
    onSelected: (RegionDistrict) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(Color.White, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
        ) {
            Text(value.name, Modifier.weight(1f), maxLines = 1)
            DropdownChevron()
        }
        MeonggoDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = values,
            selected = value,
            optionLabel = { it.name },
            onSelected = {
                expanded = false
                onSelected(it)
            },
        )
    }
}

@Composable
private fun RegionIllustration() {
    val innerColor = MaterialTheme.colorScheme.surface
    val pinColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(180.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(92.dp)) {
            val pin =
                Path().apply {
                    moveTo(size.width / 2f, size.height)
                    cubicTo(
                        size.width * .38f,
                        size.height * .72f,
                        size.width * .14f,
                        size.height * .48f,
                        size.width * .14f,
                        size.height * .34f,
                    )
                    cubicTo(size.width * .14f, size.height * .13f, size.width * .3f, 0f, size.width / 2f, 0f)
                    cubicTo(
                        size.width * .7f,
                        0f,
                        size.width * .86f,
                        size.height * .13f,
                        size.width * .86f,
                        size.height * .34f,
                    )
                    cubicTo(
                        size.width * .86f,
                        size.height * .48f,
                        size.width * .62f,
                        size.height * .72f,
                        size.width / 2f,
                        size.height,
                    )
                    close()
                }
            drawPath(pin, color = pinColor)
            drawCircle(
                color = innerColor,
                radius = size.width * .13f,
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * .31f),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 882)
@Composable
private fun RegionSelectionPreview() {
    MeonggoBanjeomTheme {
        RegionSelectionScreen(
            uiState = RegionSelectionUiState(),
            onProvinceSelected = {},
            onDistrictSelected = {},
            onSelectionComplete = {},
            onLostTabClick = {},
        )
    }
}
