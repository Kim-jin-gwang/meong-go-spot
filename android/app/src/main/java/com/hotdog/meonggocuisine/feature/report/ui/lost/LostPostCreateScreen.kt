package com.hotdog.meonggocuisine.feature.report.ui.lost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoChoiceRow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.media.PickMultipleVisualMediaWithFallback
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventDateField
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeFields
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PhotoRejectionDialog
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateConsentRow
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateFieldLabel
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateNoticeDialog
import com.hotdog.meonggocuisine.feature.report.ui.PostCreatePhotoPicker
import com.hotdog.meonggocuisine.feature.report.ui.PostCreatePhotoTips
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateStep
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateTextField
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateWizard
import com.hotdog.meonggocuisine.feature.report.ui.RegionDistrictField

@Composable
fun LostPostCreateRouteScreen(
    onBackClick: () -> Unit,
    onCreateSuccess: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LostPostCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val photoPicker =
        rememberLauncherForActivityResult(
            contract = PickMultipleVisualMediaWithFallback(LostPostCreateInputValidator.MAX_PHOTO_COUNT),
        ) { uris ->
            viewModel.addPhotos(uris.map { it.toString() })
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LostPostCreateEvent.Created -> onCreateSuccess(event.postId)
            }
        }
    }

    LostPostCreateScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onAddPhotosClick = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onRemovePhoto = viewModel::removePhoto,
        onDismissPhotoRejection = viewModel::dismissPhotoRejectionNotice,
        onNameChange = viewModel::onNameChange,
        onSpeciesChange = viewModel::onSpeciesChange,
        onBreedNameChange = viewModel::onBreedNameChange,
        onSexChange = viewModel::onSexChange,
        onColorChange = viewModel::onColorChange,
        onEventDateChange = viewModel::onEventDateChange,
        onEventPeriodChange = viewModel::onEventPeriodChange,
        onEventHourChange = viewModel::onEventHourChange,
        onEventMinuteChange = viewModel::onEventMinuteChange,
        onFieldLeave = viewModel::onFieldLeave,
        onEventPlaceChange = viewModel::onEventPlaceChange,
        onRegionSelected = viewModel::onRegionSelect,
        onExactLocationVisibleChange = viewModel::onExactLocationVisibleChange,
        onFeatureTextChange = viewModel::onFeatureTextChange,
        onSubmitClick = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
fun LostPostCreateScreen(
    uiState: LostPostCreateUiState,
    onBackClick: () -> Unit,
    onAddPhotosClick: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDismissPhotoRejection: () -> Unit,
    onNameChange: (String) -> Unit,
    onSpeciesChange: (AnimalSpeciesOption) -> Unit,
    onBreedNameChange: (String) -> Unit,
    onSexChange: (AnimalSexOption) -> Unit,
    onColorChange: (String) -> Unit,
    onEventDateChange: (String) -> Unit,
    onEventPeriodChange: (DayPeriod) -> Unit,
    onEventHourChange: (String) -> Unit,
    onEventMinuteChange: (String) -> Unit,
    onFieldLeave: (LostPostField) -> Unit,
    onEventPlaceChange: (String) -> Unit,
    onRegionSelected: (String?, String?) -> Unit,
    onExactLocationVisibleChange: (Boolean) -> Unit,
    onFeatureTextChange: (String) -> Unit,
    onSubmitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 어느 단계에 무엇이 남았는지 칸 단위로 가른다. 이름 글자를 맞춰 보면 문구를 고치는 순간
    // 조용히 어긋난다.
    fun missing(vararg fields: LostPostField) = uiState.blockerFields.filter { it in fields }.map { it.label }

    PostCreateWizard(
        title = "실종동물 등록",
        steps =
            listOf(
                PostCreateStep(
                    name = "사진 등록",
                    heading = "사진 등록",
                    missing = missing(LostPostField.PHOTOS),
                    bottomContent = { PostCreatePhotoTips() },
                ) {
                    PostCreatePhotoPicker(
                        photoUris = uiState.photoUris,
                        maxCount = LostPostCreateInputValidator.MAX_PHOTO_COUNT,
                        onAddPhotosClick = onAddPhotosClick,
                        onRemovePhoto = onRemovePhoto,
                    )
                    uiState.photoRejectionNotice?.let { notice ->
                        PhotoRejectionDialog(notice = notice, onDismiss = onDismissPhotoRejection)
                    }
                },
                PostCreateStep(
                    name = "기본 정보",
                    heading = "기본 정보",
                ) {
                    // 반드시 고르는 둘을 위로 올린다. 아래에 두면 고를 것과 비워도 되는 것이
                    // 섞여, 선택 칸을 지나치느라 필수 칸을 뒤에서 만난다.
                    PostCreateFieldLabel("동물 종류")
                    MeonggoChoiceRow(
                        options = AnimalSpeciesOption.entries,
                        selected = uiState.species,
                        label = { it.label },
                        onSelect = onSpeciesChange,
                    )
                    PostCreateFieldLabel("성별")
                    MeonggoChoiceRow(
                        options = AnimalSexOption.entries,
                        selected = uiState.sex,
                        label = { it.label },
                        onSelect = onSexChange,
                    )
                    PostCreateFieldLabel("이름 (선택)")
                    PostCreateTextField(
                        value = uiState.name,
                        onValueChange = onNameChange,
                        placeholder = "이름을 입력하세요",
                    )
                    PostCreateFieldLabel("품종 (선택)")
                    PostCreateTextField(
                        value = uiState.breedName,
                        onValueChange = onBreedNameChange,
                        placeholder = "품종을 입력하세요",
                    )
                    PostCreateFieldLabel("색상 (선택)")
                    PostCreateTextField(
                        value = uiState.color,
                        onValueChange = onColorChange,
                        placeholder = "색상을 입력하세요",
                    )
                },
                PostCreateStep(
                    name = "실종 정보",
                    heading = "실종 정보",
                    missing =
                        missing(
                            LostPostField.EVENT_DATE,
                            LostPostField.EVENT_TIME,
                            LostPostField.REGION,
                            LostPostField.EVENT_PLACE,
                        ),
                ) {
                    PostCreateFieldLabel("실종 날짜")
                    // 달력에서 고른 날짜는 여덟 자리가 채워진 값이라 뷰모델이 바로 검사한다 — 떠남 콜백이 필요 없다.
                    EventDateField(
                        value = uiState.eventDate,
                        onValueChange = onEventDateChange,
                        errorMessage = uiState.eventDateError,
                    )
                    PostCreateFieldLabel("실종 시간")
                    EventTimeFields(
                        value = uiState.eventTime,
                        onPeriodChange = onEventPeriodChange,
                        onHourChange = onEventHourChange,
                        onMinuteChange = onEventMinuteChange,
                        onMinuteLeave = { onFieldLeave(LostPostField.EVENT_TIME) },
                        errorMessage = uiState.eventTimeError,
                    )
                    PostCreateFieldLabel("실종 지역 (시·군·구)")
                    RegionDistrictField(
                        selectedRegionCode = uiState.selectedRegionCode,
                        onRegionSelected = onRegionSelected,
                        errorMessage = uiState.regionError,
                    )
                    PostCreateFieldLabel("실종 장소")
                    PostCreateTextField(
                        value = uiState.eventPlace,
                        onValueChange = onEventPlaceChange,
                        placeholder = "실종 장소를 입력하세요",
                        errorMessage = uiState.eventPlaceError,
                        onLeave = { onFieldLeave(LostPostField.EVENT_PLACE) },
                    )
                    PostCreateConsentRow(
                        text = "실종 장소의 정확한 위치를 공개하는 데 동의합니다.",
                        checked = uiState.exactLocationVisible,
                        onCheckedChange = onExactLocationVisibleChange,
                    )
                    PostCreateFieldLabel("특징 (선택)")
                    PostCreateTextField(
                        value = uiState.featureText,
                        onValueChange = onFeatureTextChange,
                        placeholder = "특이사항을 입력하세요",
                        singleLine = false,
                    )
                },
            ),
        onExit = onBackClick,
        submitLabel = "등록하기",
        canSubmit = uiState.canSubmit,
        isSubmitting = uiState.isSubmitting,
        onSubmit = onSubmitClick,
        loadingDescription = "실종동물 등록 요청 중",
        requestError = uiState.requestError,
        modifier = modifier,
    )

    // 사진 문제는 창으로 알린다. 마법사 밖에 두어야 다른 단계에서 등록을 눌렀을 때도 보인다.
    // 고른 사진이 걸러진 경우에는 그 창이 이미 같은 내용을 더 자세히 말하므로 겹쳐 띄우지 않는다.
    var dismissedPhotoError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.photoError) {
        if (uiState.photoError == null) dismissedPhotoError = null
    }
    uiState.photoError
        ?.takeIf { it != dismissedPhotoError && uiState.photoRejectionNotice == null }
        ?.let { message ->
            PostCreateNoticeDialog(message = message, onDismiss = { dismissedPhotoError = message })
        }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 900, backgroundColor = 0xFFFDFBF7)
@Composable
private fun LostPostCreateScreenPreview() {
    MeonggoBanjeomTheme {
        LostPostCreateScreen(
            uiState =
                LostPostCreateUiState(
                    eventDate = "2026-09-09",
                    eventTime = EventTimeInput(DayPeriod.PM, "5", "30"),
                    eventPlace = "서울특별시 마포구 월드컵북로",
                    selectedRegionCode = "11440",
                    selectedRegionName = "서울특별시 마포구",
                ),
            onBackClick = {},
            onAddPhotosClick = {},
            onRemovePhoto = {},
            onDismissPhotoRejection = {},
            onNameChange = {},
            onSpeciesChange = {},
            onBreedNameChange = {},
            onSexChange = {},
            onColorChange = {},
            onEventDateChange = {},
            onEventPeriodChange = {},
            onEventHourChange = {},
            onEventMinuteChange = {},
            onFieldLeave = {},
            onEventPlaceChange = {},
            onRegionSelected = { _, _ -> },
            onExactLocationVisibleChange = {},
            onFeatureTextChange = {},
            onSubmitClick = {},
        )
    }
}
