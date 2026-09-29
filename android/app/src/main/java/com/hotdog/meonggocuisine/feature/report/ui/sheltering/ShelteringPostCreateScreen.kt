package com.hotdog.meonggocuisine.feature.report.ui.sheltering

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
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoChoiceGrid
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoChoiceRow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.media.PickMultipleVisualMediaWithFallback
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventDateField
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeFields
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PhotoRejectionDialog
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateConsentRow
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateErrorText
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateFieldLabel
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateNoticeDialog
import com.hotdog.meonggocuisine.feature.report.ui.PostCreatePhotoPicker
import com.hotdog.meonggocuisine.feature.report.ui.PostCreatePhotoTips
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateStep
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateTextField
import com.hotdog.meonggocuisine.feature.report.ui.PostCreateWizard
import com.hotdog.meonggocuisine.feature.report.ui.RegionDistrictField
import com.hotdog.meonggocuisine.feature.report.ui.lost.AnimalSexOption
import com.hotdog.meonggocuisine.feature.report.ui.lost.AnimalSpeciesOption
import com.hotdog.meonggocuisine.feature.report.ui.rememberCameraCapture

@Composable
fun ShelteringPostCreateRouteScreen(
    onBackClick: () -> Unit,
    onCreateSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelteringPostCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val photoPicker =
        rememberLauncherForActivityResult(
            contract = PickMultipleVisualMediaWithFallback(ShelteringPostCreateInputValidator.MAX_PHOTO_COUNT),
        ) { uris ->
            viewModel.addPhotos(uris.map { it.toString() })
        }
    // 보호소가 아닌 일반 사용자는 발견한 자리에서 바로 찍어 올리는 경우가 많다 — 앨범 선택 옆에 촬영을 둔다.
    val takePhoto =
        rememberCameraCapture(
            onCaptured = { uri -> viewModel.addPhotos(listOf(uri.toString())) },
            onUnavailable = { viewModel.onPhotoSourceUnavailable("이 기기에서는 카메라 앱을 열 수 없어요. 앨범에서 골라 주세요.") },
        )

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == ShelteringPostCreateEvent.Created) onCreateSuccess()
        }
    }

    ShelteringPostCreateScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onAddPhotosClick = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onTakePhotoClick = takePhoto,
        onRemovePhoto = viewModel::removePhoto,
        onDismissPhotoRejection = viewModel::dismissPhotoRejectionNotice,
        onNameChange = viewModel::onNameChange,
        onSpeciesChange = viewModel::onSpeciesChange,
        onBreedNameChange = viewModel::onBreedNameChange,
        onSexChange = viewModel::onSexChange,
        onColorChange = viewModel::onColorChange,
        onFoundDateChange = viewModel::onFoundDateChange,
        onFoundPeriodChange = viewModel::onFoundPeriodChange,
        onFoundHourChange = viewModel::onFoundHourChange,
        onFoundMinuteChange = viewModel::onFoundMinuteChange,
        onFieldLeave = viewModel::onFieldLeave,
        onFoundPlaceChange = viewModel::onFoundPlaceChange,
        onRegionSelected = viewModel::onRegionSelect,
        onFoundLocationVisibleChange = viewModel::onFoundLocationVisibleChange,
        onProtectionStatusChange = viewModel::onProtectionStatusChange,
        onCurrentProtectionPlaceChange = viewModel::onCurrentProtectionPlaceChange,
        onCurrentLocationVisibleChange = viewModel::onCurrentLocationVisibleChange,
        onFeatureTextChange = viewModel::onFeatureTextChange,
        onSubmitClick = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
fun ShelteringPostCreateScreen(
    uiState: ShelteringPostCreateUiState,
    onBackClick: () -> Unit,
    onAddPhotosClick: () -> Unit,
    onTakePhotoClick: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDismissPhotoRejection: () -> Unit,
    onNameChange: (String) -> Unit,
    onSpeciesChange: (AnimalSpeciesOption) -> Unit,
    onBreedNameChange: (String) -> Unit,
    onSexChange: (AnimalSexOption) -> Unit,
    onColorChange: (String) -> Unit,
    onFoundDateChange: (String) -> Unit,
    onFoundPeriodChange: (DayPeriod) -> Unit,
    onFoundHourChange: (String) -> Unit,
    onFoundMinuteChange: (String) -> Unit,
    onFieldLeave: (ShelteringPostField) -> Unit,
    onFoundPlaceChange: (String) -> Unit,
    onRegionSelected: (String?, String?) -> Unit,
    onFoundLocationVisibleChange: (Boolean) -> Unit,
    onProtectionStatusChange: (String) -> Unit,
    onCurrentProtectionPlaceChange: (String) -> Unit,
    onCurrentLocationVisibleChange: (Boolean) -> Unit,
    onFeatureTextChange: (String) -> Unit,
    onSubmitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 어느 단계에 무엇이 남았는지 칸 단위로 가른다 — 실종 등록과 같은 방식.
    fun missing(vararg fields: ShelteringPostField) = uiState.blockerFields.filter { it in fields }.map { it.label }

    PostCreateWizard(
        title = "보호동물 등록",
        steps =
            listOf(
                PostCreateStep(
                    name = "사진 등록",
                    heading = "사진 등록",
                    missing = missing(ShelteringPostField.PHOTOS),
                    bottomContent = { PostCreatePhotoTips() },
                ) {
                    PostCreatePhotoPicker(
                        photoUris = uiState.photoUris,
                        maxCount = ShelteringPostCreateInputValidator.MAX_PHOTO_COUNT,
                        onAddPhotosClick = onAddPhotosClick,
                        onRemovePhoto = onRemovePhoto,
                        onTakePhotoClick = onTakePhotoClick,
                    )
                    uiState.photoRejectionNotice?.let { notice ->
                        PhotoRejectionDialog(notice = notice, onDismiss = onDismissPhotoRejection)
                    }
                },
                PostCreateStep(
                    name = "기본 정보",
                    heading = "기본 정보",
                ) {
                    // 반드시 고르는 둘을 위로 — 실종 등록과 같은 순서.
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
                    name = "발견 정보",
                    heading = "발견 정보",
                    missing =
                        missing(
                            ShelteringPostField.FOUND_DATE,
                            ShelteringPostField.FOUND_TIME,
                            ShelteringPostField.REGION,
                            ShelteringPostField.FOUND_PLACE,
                        ),
                ) {
                    PostCreateFieldLabel("발견 날짜")
                    // 달력에서 고른 날짜는 여덟 자리가 채워진 값이라 뷰모델이 바로 검사한다 — 떠남 콜백이 필요 없다.
                    EventDateField(
                        value = uiState.foundDate,
                        onValueChange = onFoundDateChange,
                        errorMessage = uiState.foundDateError,
                    )
                    PostCreateFieldLabel("발견 시간")
                    EventTimeFields(
                        value = uiState.foundTime,
                        onPeriodChange = onFoundPeriodChange,
                        onHourChange = onFoundHourChange,
                        onMinuteChange = onFoundMinuteChange,
                        onMinuteLeave = { onFieldLeave(ShelteringPostField.FOUND_TIME) },
                        errorMessage = uiState.foundTimeError,
                    )
                    PostCreateFieldLabel("발견 지역 (시·군·구)")
                    RegionDistrictField(
                        selectedRegionCode = uiState.selectedRegionCode,
                        onRegionSelected = onRegionSelected,
                        errorMessage = uiState.regionError,
                    )
                    PostCreateFieldLabel("발견 장소")
                    PostCreateTextField(
                        value = uiState.foundPlace,
                        onValueChange = onFoundPlaceChange,
                        placeholder = "발견 장소를 입력하세요",
                        errorMessage = uiState.foundPlaceError,
                        onLeave = { onFieldLeave(ShelteringPostField.FOUND_PLACE) },
                    )
                    PostCreateConsentRow(
                        text = "발견 장소의 정확한 위치를 공개하는 데 동의합니다.",
                        checked = uiState.foundLocationVisible,
                        onCheckedChange = onFoundLocationVisibleChange,
                    )
                },
                PostCreateStep(
                    name = "보호 정보",
                    heading = "보호 정보",
                    missing =
                        missing(
                            ShelteringPostField.PROTECTION_STATUS,
                            ShelteringPostField.CURRENT_PROTECTION_PLACE,
                        ),
                ) {
                    PostCreateFieldLabel("현재 보호 상태")
                    MeonggoChoiceGrid(
                        options = ProtectionStatusOption.entries,
                        selected = ProtectionStatusOption.from(uiState.protectionStatus),
                        label = { it.label },
                        onSelect = { onProtectionStatusChange(it.label) },
                    )
                    uiState.protectionStatusError?.let { PostCreateErrorText(it) }
                    PostCreateFieldLabel("현재 보호 장소")
                    PostCreateTextField(
                        value = uiState.currentProtectionPlace,
                        onValueChange = onCurrentProtectionPlaceChange,
                        placeholder = "현재 보호 장소를 입력하세요",
                        errorMessage = uiState.currentProtectionPlaceError,
                        onLeave = { onFieldLeave(ShelteringPostField.CURRENT_PROTECTION_PLACE) },
                    )
                    PostCreateConsentRow(
                        text = "보호 장소의 정확한 위치를 공개하는 데 동의합니다.",
                        checked = uiState.currentLocationVisible,
                        onCheckedChange = onCurrentLocationVisibleChange,
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
        loadingDescription = "보호 동물 등록 요청 중",
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

@Preview(showBackground = true, widthDp = 411, heightDp = 1000, backgroundColor = 0xFFFDFBF7)
@Composable
private fun ShelteringPostCreateScreenPreview() {
    MeonggoBanjeomTheme {
        ShelteringPostCreateScreen(
            uiState =
                ShelteringPostCreateUiState(
                    foundDate = "2026-09-10",
                    foundTime = EventTimeInput(DayPeriod.PM, "2", "20"),
                    foundPlace = "서울특별시 마포구 공원 인근",
                    protectionStatus = "임시 보호 중",
                    currentProtectionPlace = "서울특별시 마포구 자택",
                    selectedRegionCode = "11440",
                    selectedRegionName = "서울특별시 마포구",
                ),
            onBackClick = {},
            onAddPhotosClick = {},
            onTakePhotoClick = {},
            onRemovePhoto = {},
            onDismissPhotoRejection = {},
            onNameChange = {},
            onSpeciesChange = {},
            onBreedNameChange = {},
            onSexChange = {},
            onColorChange = {},
            onFoundDateChange = {},
            onFoundPeriodChange = {},
            onFoundHourChange = {},
            onFoundMinuteChange = {},
            onFieldLeave = {},
            onFoundPlaceChange = {},
            onRegionSelected = { _, _ -> },
            onFoundLocationVisibleChange = {},
            onProtectionStatusChange = {},
            onCurrentProtectionPlaceChange = {},
            onCurrentLocationVisibleChange = {},
            onFeatureTextChange = {},
            onSubmitClick = {},
        )
    }
}
