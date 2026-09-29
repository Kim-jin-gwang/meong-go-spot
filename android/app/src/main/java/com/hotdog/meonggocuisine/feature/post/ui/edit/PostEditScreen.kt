package com.hotdog.meonggocuisine.feature.post.ui.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoChoiceGrid
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoChoiceRow
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.media.PickMultipleVisualMediaWithFallback
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoSource
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventDateField
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeFields
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateErrorText
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateFieldLabel
import com.hotdog.meonggocuisine.feature.report.ui.PostCreatePhotoStrip
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateTextField
import com.hotdog.meonggocuisine.feature.report.ui.sheltering.ProtectionStatusOption

@Composable
fun PostEditRouteScreen(
    onBackClick: () -> Unit,
    onUpdateSuccess: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val photoPicker =
        rememberLauncherForActivityResult(
            contract = PickMultipleVisualMediaWithFallback(PostEditInputValidator.MAX_PHOTO_COUNT),
        ) { uris ->
            viewModel.onAddPhotos(uris.map { it.toString() })
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PostEditEvent.Updated -> onUpdateSuccess(event.postId)
            }
        }
    }

    PostEditScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onAddPhotosClick = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onRemovePhoto = viewModel::onRemovePhoto,
        onNameChange = viewModel::onNameChange,
        onBreedNameChange = viewModel::onBreedNameChange,
        onSexChange = viewModel::onSexChange,
        onColorChange = viewModel::onColorChange,
        onEventDateChange = viewModel::onEventDateChange,
        onEventPeriodChange = viewModel::onEventPeriodChange,
        onEventHourChange = viewModel::onEventHourChange,
        onEventMinuteChange = viewModel::onEventMinuteChange,
        onEventTimeLeave = viewModel::onEventTimeLeave,
        onEventPlaceChange = viewModel::onEventPlaceChange,
        onCurrentPlaceChange = viewModel::onCurrentPlaceChange,
        onFeatureTextChange = viewModel::onFeatureTextChange,
        onProtectionStatusChange = viewModel::onProtectionStatusChange,
        onRefreshClick = viewModel::refresh,
        onRetryLoadClick = viewModel::retryLoad,
        onSubmitClick = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
fun PostEditScreen(
    uiState: PostEditUiState,
    onBackClick: () -> Unit,
    onAddPhotosClick: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onBreedNameChange: (String) -> Unit,
    onSexChange: (AnimalSexOption) -> Unit,
    onColorChange: (String) -> Unit,
    onEventDateChange: (String) -> Unit,
    onEventPeriodChange: (DayPeriod) -> Unit,
    onEventHourChange: (String) -> Unit,
    onEventMinuteChange: (String) -> Unit,
    onEventTimeLeave: () -> Unit,
    onEventPlaceChange: (String) -> Unit,
    onCurrentPlaceChange: (String) -> Unit,
    onFeatureTextChange: (String) -> Unit,
    onProtectionStatusChange: (ProtectionStatusOption) -> Unit,
    onRefreshClick: () -> Unit,
    onRetryLoadClick: () -> Unit,
    onSubmitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
    ) {
        MeonggoScreenHeader(title = "게시물 수정", onBackClick = onBackClick)
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MeonggoSurfaces.gutter)
                    .padding(bottom = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MeonggoSurfaces.cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                elevation = CardDefaults.cardElevation(defaultElevation = MeonggoSurfaces.cardElevation),
            ) {
                when {
                    uiState.isLoading -> LoadingBody()
                    uiState.loadErrorMessage != null ->
                        LoadFailedBody(message = uiState.loadErrorMessage, onRetryLoadClick = onRetryLoadClick)
                    else ->
                        EditForm(
                            uiState = uiState,
                            onAddPhotosClick = onAddPhotosClick,
                            onRemovePhoto = onRemovePhoto,
                            onNameChange = onNameChange,
                            onBreedNameChange = onBreedNameChange,
                            onSexChange = onSexChange,
                            onColorChange = onColorChange,
                            onEventDateChange = onEventDateChange,
                            onEventPeriodChange = onEventPeriodChange,
                            onEventHourChange = onEventHourChange,
                            onEventMinuteChange = onEventMinuteChange,
                            onEventTimeLeave = onEventTimeLeave,
                            onEventPlaceChange = onEventPlaceChange,
                            onCurrentPlaceChange = onCurrentPlaceChange,
                            onFeatureTextChange = onFeatureTextChange,
                            onProtectionStatusChange = onProtectionStatusChange,
                            onRefreshClick = onRefreshClick,
                            onSubmitClick = onSubmitClick,
                        )
                }
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(
        modifier = Modifier.fillMaxWidth().height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        MeonggoLoadingIndicator(contentDescription = "게시물 정보를 불러오는 중")
    }
}

@Composable
private fun LoadFailedBody(
    message: String,
    onRetryLoadClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onRetryLoadClick, shape = RoundedCornerShape(12.dp)) {
            Text("다시 불러오기")
        }
    }
}

@Composable
private fun EditForm(
    uiState: PostEditUiState,
    onAddPhotosClick: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onBreedNameChange: (String) -> Unit,
    onSexChange: (AnimalSexOption) -> Unit,
    onColorChange: (String) -> Unit,
    onEventDateChange: (String) -> Unit,
    onEventPeriodChange: (DayPeriod) -> Unit,
    onEventHourChange: (String) -> Unit,
    onEventMinuteChange: (String) -> Unit,
    onEventTimeLeave: () -> Unit,
    onEventPlaceChange: (String) -> Unit,
    onCurrentPlaceChange: (String) -> Unit,
    onFeatureTextChange: (String) -> Unit,
    onProtectionStatusChange: (ProtectionStatusOption) -> Unit,
    onRefreshClick: () -> Unit,
    onSubmitClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        uiState.versionConflictMessage?.let { message ->
            VersionConflictNotice(message = message, onRefreshClick = onRefreshClick)
        }
        PhotoSection(
            uiState = uiState,
            onAddPhotosClick = onAddPhotosClick,
            onRemovePhoto = onRemovePhoto,
        )
        BasicInfoSection(
            uiState = uiState,
            onNameChange = onNameChange,
            onBreedNameChange = onBreedNameChange,
            onSexChange = onSexChange,
            onColorChange = onColorChange,
        )
        EventInfoSection(
            uiState = uiState,
            onEventDateChange = onEventDateChange,
            onEventPeriodChange = onEventPeriodChange,
            onEventHourChange = onEventHourChange,
            onEventMinuteChange = onEventMinuteChange,
            onEventTimeLeave = onEventTimeLeave,
            onEventPlaceChange = onEventPlaceChange,
            onCurrentPlaceChange = onCurrentPlaceChange,
            onFeatureTextChange = onFeatureTextChange,
            onProtectionStatusChange = onProtectionStatusChange,
            onSubmitClick = onSubmitClick,
        )
    }
}

/**
 * POST-004 안내입니다. 입력을 지우지 않고 최신 내용으로 다시 불러올 동선만 제공한다.
 */
@Composable
private fun VersionConflictNotice(
    message: String,
    onRefreshClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "다시 불러오면 입력한 내용이 서버의 최신 내용으로 바뀝니다.",
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onRefreshClick,
                modifier = Modifier.height(38.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("최신 내용 다시 불러오기", fontSize = 13.sp)
            }
        }
    }
}

/**
 * 사진을 한 장씩 빼고 더하는 자리입니다.
 *
 * 서버는 최종 전체만 받지만(P5) 화면까지 "전부 다시 고르기"로 둘 이유는 없다. 한 장만 잘못
 * 올린 사람이 나머지 아홉 장을 다시 찾아 고르게 하던 게 이전 모양이었다.
 */
@Composable
private fun PhotoSection(
    uiState: PostEditUiState,
    onAddPhotosClick: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    Section(title = "1. 사진") {
        PostCreatePhotoStrip(
            photoUris = uiState.photoRefs,
            maxCount = PostEditInputValidator.MAX_PHOTO_COUNT,
            onAddClick = onAddPhotosClick,
            onRemovePhoto = onRemovePhoto,
        )
        uiState.photoError?.let { PostCreateErrorText(it) }
    }
}

@Composable
private fun BasicInfoSection(
    uiState: PostEditUiState,
    onNameChange: (String) -> Unit,
    onBreedNameChange: (String) -> Unit,
    onSexChange: (AnimalSexOption) -> Unit,
    onColorChange: (String) -> Unit,
) {
    Section(title = "2. 기본 정보") {
        PostCreateFieldLabel("이름 (선택)")
        PostCreateTextField(uiState.name, onNameChange, "이름을 입력하세요")
        PostCreateFieldLabel("동물 종류")
        ReadOnlyValue(uiState.speciesLabel)
        PostCreateFieldLabel("품종 (선택)")
        PostCreateTextField(uiState.breedName, onBreedNameChange, "품종을 입력하세요")
        PostCreateFieldLabel("성별")
        MeonggoChoiceRow(
            options = AnimalSexOption.entries,
            selected = uiState.sex,
            label = { it.label },
            onSelect = onSexChange,
        )
        PostCreateFieldLabel("색상 (선택)")
        PostCreateTextField(uiState.color, onColorChange, "색상을 입력하세요")
    }
}

/**
 * 사건 정보입니다. 칸 이름과 부품을 등록 화면과 같은 것으로 맞춘다.
 *
 * 정확한 위치 공개 스위치는 두지 않는다. 공개 여부는 등록할 때 고른 값을 그대로 이어 보낸다.
 */
@Composable
private fun EventInfoSection(
    uiState: PostEditUiState,
    onEventDateChange: (String) -> Unit,
    onEventPeriodChange: (DayPeriod) -> Unit,
    onEventHourChange: (String) -> Unit,
    onEventMinuteChange: (String) -> Unit,
    onEventTimeLeave: () -> Unit,
    onEventPlaceChange: (String) -> Unit,
    onCurrentPlaceChange: (String) -> Unit,
    onFeatureTextChange: (String) -> Unit,
    onProtectionStatusChange: (ProtectionStatusOption) -> Unit,
    onSubmitClick: () -> Unit,
) {
    val dateLabel = if (uiState.isSheltering) "발견 날짜" else "실종 날짜"
    val timeLabel = if (uiState.isSheltering) "발견 시간" else "실종 시간"
    val placeLabel = if (uiState.isSheltering) "발견 장소" else "실종 장소"

    Section(title = "3. ${if (uiState.isSheltering) "발견" else "실종"} 정보") {
        PostCreateFieldLabel(dateLabel)
        EventDateField(
            value = uiState.eventDate,
            onValueChange = onEventDateChange,
            errorMessage = uiState.eventDateError,
        )
        PostCreateFieldLabel(timeLabel)
        EventTimeFields(
            value = uiState.eventTime,
            onPeriodChange = onEventPeriodChange,
            onHourChange = onEventHourChange,
            onMinuteChange = onEventMinuteChange,
            onMinuteLeave = onEventTimeLeave,
            errorMessage = uiState.eventTimeError,
        )
        PostCreateFieldLabel(placeLabel)
        PostCreateTextField(
            value = uiState.eventPlace,
            onValueChange = onEventPlaceChange,
            placeholder = "${placeLabel}를 입력하세요",
            errorMessage = uiState.eventPlaceError,
        )
        if (uiState.isSheltering) {
            PostCreateFieldLabel("현재 보호 상태")
            MeonggoChoiceGrid(
                options = ProtectionStatusOption.entries,
                selected = uiState.protectionStatus,
                label = { it.label },
                onSelect = onProtectionStatusChange,
            )
            // 등록 화면이 생기기 전에 손으로 적힌 값은 선택지에 없다. 뭐가 저장돼 있는지는 보여 주고 고를지는 맡긴다.
            uiState.unlistedProtectionStatus?.let { saved ->
                Text(
                    text = "지금 저장된 값: $saved",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.protectionStatusError?.let { PostCreateErrorText(it) }
            PostCreateFieldLabel("현재 보호 장소")
            PostCreateTextField(
                value = uiState.currentPlace,
                onValueChange = onCurrentPlaceChange,
                placeholder = "현재 보호 장소를 입력하세요",
                errorMessage = uiState.currentPlaceError,
            )
        }
        PostCreateFieldLabel("특징 (선택)")
        PostCreateTextField(
            uiState.featureText,
            onFeatureTextChange,
            "특이사항을 입력하세요",
            singleLine = false,
        )
        uiState.requestError?.let { PostCreateErrorText(it) }
        Button(
            onClick = onSubmitClick,
            enabled = uiState.canSubmit,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            if (uiState.isSubmitting) {
                MeonggoLoadingIndicator(
                    contentDescription = "게시물 수정 요청 중",
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text("수정 내용 저장", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    suffix: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
            suffix?.let {
                Text(" $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        content()
    }
}

/**
 * 못 바꾸는 값입니다.
 *
 * 테두리·높이·글자 크기를 옆의 입력칸과 똑같이 맞추고 글자만 흐리게 둔다. 줄마다 상자 모양이
 * 달라지면 무엇을 고칠 수 있는지가 아니라 화면이 덜 만들어진 것처럼 보인다.
 */
@Composable
private fun ReadOnlyValue(value: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OutlinedTextFieldDefaults.MinHeight)
                .clip(ReadOnlyShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outline, ReadOnlyShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
        )
    }
}

private val ReadOnlyShape = RoundedCornerShape(14.dp)
private val SamplePhotoUrls = listOf("sample://photo/1", "sample://photo/2", "sample://photo/3")

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PostEditLostScreenPreview() {
    MeonggoBanjeomTheme {
        PostEditScreen(
            uiState = previewState(),
            onBackClick = {},
            onAddPhotosClick = {},
            onRemovePhoto = {},
            onNameChange = {},
            onBreedNameChange = {},
            onSexChange = {},
            onColorChange = {},
            onEventDateChange = {},
            onEventPeriodChange = {},
            onEventHourChange = {},
            onEventMinuteChange = {},
            onEventTimeLeave = {},
            onEventPlaceChange = {},
            onCurrentPlaceChange = {},
            onFeatureTextChange = {},
            onProtectionStatusChange = {},
            onRefreshClick = {},
            onRetryLoadClick = {},
            onSubmitClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1000, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PostEditShelteringScreenPreview() {
    MeonggoBanjeomTheme {
        PostEditScreen(
            uiState =
                previewState().copy(
                    isSheltering = true,
                    name = "",
                    protectionStatus = ProtectionStatusOption.TEMPORARY,
                    currentPlace = "은평구 갈현동 자택",
                    currentPlaceVisible = false,
                ),
            onBackClick = {},
            onAddPhotosClick = {},
            onRemovePhoto = {},
            onNameChange = {},
            onBreedNameChange = {},
            onSexChange = {},
            onColorChange = {},
            onEventDateChange = {},
            onEventPeriodChange = {},
            onEventHourChange = {},
            onEventMinuteChange = {},
            onEventTimeLeave = {},
            onEventPlaceChange = {},
            onCurrentPlaceChange = {},
            onFeatureTextChange = {},
            onProtectionStatusChange = {},
            onRefreshClick = {},
            onRetryLoadClick = {},
            onSubmitClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PostEditVersionConflictPreview() {
    MeonggoBanjeomTheme {
        PostEditScreen(
            uiState =
                previewState().copy(
                    versionConflictMessage = "게시물이 변경되었습니다. 최신 내용을 다시 확인해 주세요.",
                ),
            onBackClick = {},
            onAddPhotosClick = {},
            onRemovePhoto = {},
            onNameChange = {},
            onBreedNameChange = {},
            onSexChange = {},
            onColorChange = {},
            onEventDateChange = {},
            onEventPeriodChange = {},
            onEventHourChange = {},
            onEventMinuteChange = {},
            onEventTimeLeave = {},
            onEventPlaceChange = {},
            onCurrentPlaceChange = {},
            onFeatureTextChange = {},
            onProtectionStatusChange = {},
            onRefreshClick = {},
            onRetryLoadClick = {},
            onSubmitClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 500, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PostEditLoadFailedPreview() {
    MeonggoBanjeomTheme {
        PostEditScreen(
            uiState = PostEditUiState(isLoading = false, loadErrorMessage = "본인이 등록한 게시물만 수정할 수 있습니다."),
            onBackClick = {},
            onAddPhotosClick = {},
            onRemovePhoto = {},
            onNameChange = {},
            onBreedNameChange = {},
            onSexChange = {},
            onColorChange = {},
            onEventDateChange = {},
            onEventPeriodChange = {},
            onEventHourChange = {},
            onEventMinuteChange = {},
            onEventTimeLeave = {},
            onEventPlaceChange = {},
            onCurrentPlaceChange = {},
            onFeatureTextChange = {},
            onProtectionStatusChange = {},
            onRefreshClick = {},
            onRetryLoadClick = {},
            onSubmitClick = {},
        )
    }
}

private fun previewState() =
    PostEditUiState(
        isLoading = false,
        postId = 1002L,
        version = 3L,
        savedPhotoUrls = SamplePhotoUrls,
        photos = SamplePhotoUrls.map(PostPhotoSource::Saved),
        name = "콩이",
        breedName = "푸들",
        sex = AnimalSexOption.MALE,
        color = "갈색",
        eventDate = "2026-08-20",
        eventTime = EventTimeInput(period = DayPeriod.PM, hour = "8", minute = "00"),
        eventPlace = "역삼역 3번 출구 인근",
        eventPlaceVisible = true,
        featureText = "빨간 목줄과 파란 옷을 착용했습니다.",
    )
