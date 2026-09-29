package com.hotdog.meonggocuisine.feature.report.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.PlusIcon
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoDashedBorder
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 등록 단계 하나입니다.
 *
 * [name] 은 진행 표시가 읽어 주는 단계 이름이고, [heading] 은 카드 안 제목이다.
 *
 * [missing] 은 이 단계에서 아직 못 채운 칸이다. 비어 있어야 `다음` 이 열린다. 화면에 적지는
 * 않는다 — 안내 줄이 있고 없고에 따라 카드 높이가 달라져 단계를 넘길 때 화면이 흔들렸다.
 */
class PostCreateStep(
    val name: String,
    val heading: String? = null,
    val suffix: String? = null,
    val missing: List<String> = emptyList(),
    /** 카드 맨 아래에 붙여 둘 것. 내용이 짧아 남는 자리를 두고 떠 있지 않게 한다. */
    val bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * 게시물 등록 화면의 겉틀입니다. 묶음을 한 번에 한 단계씩 보여 준다.
 *
 * 한 화면에 다 쌓으면 스크롤이 길어서 지금 어디쯤인지, 얼마나 남았는지 알 수 없었다. 나눠 두면
 * 화면마다 할 일이 하나고 위쪽 막대가 남은 양을 보여 준다.
 *
 * 한 단계를 채워야 다음으로 간다. 다 지나온 뒤에 한꺼번에 돌려보내면 어느 화면의 어느 칸이
 * 문제였는지 되짚어야 한다.
 *
 * 실종·보호 두 등록 화면이 서로 복사본이라 한쪽만 고치면 같은 일을 하는 화면이 갈린다 — 목록
 * 두 화면이 실제로 그렇게 어긋났었다. 겉틀과 칸 모양을 여기 하나로 두고 둘이 같이 쓴다.
 */
@Composable
fun PostCreateWizard(
    title: String,
    steps: List<PostCreateStep>,
    onExit: () -> Unit,
    submitLabel: String,
    canSubmit: Boolean,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
    loadingDescription: String,
    modifier: Modifier = Modifier,
    requestError: String? = null,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = index.coerceIn(0, steps.lastIndex)
    val isLast = current == steps.lastIndex

    // 기기 뒤로가기는 화면을 나가기 전에 단계를 먼저 되짚는다. 세 단계를 채운 사람이 뒤로가기
    // 한 번에 전부 잃으면 안 된다.
    BackHandler(enabled = current > 0) { index = current - 1 }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .imePadding()
                .navigationBarsPadding(),
    ) {
        MeonggoScreenHeader(
            title = title,
            onBackClick = { if (current > 0) index = current - 1 else onExit() },
        )
        val step = steps[current]
        // 단계를 옮길 때마다 새 스크롤을 만든다. 앞 단계에서 내려 둔 자리 그대로 열리면 다음
        // 단계가 중간부터 보인다.
        key(current) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = MeonggoSurfaces.gutter)
                        .padding(bottom = ScrollBottomInset),
            ) {
                StepProgress(steps = steps, current = current, onStepClick = { index = it })
                // 카드가 남은 높이를 다 쓴다. 내용에 맞춰 줄었다 늘었다 하면 단계를 넘길 때마다
                // 흰 면의 크기가 달라져 화면이 갈아엎히는 것처럼 보인다. 내용이 넘치면 카드
                // 안에서 스크롤한다.
                PostCreateSection(
                    modifier = Modifier.weight(1f),
                    suffix = step.suffix,
                    bottomContent = step.bottomContent,
                ) {
                    step.heading?.let { StepHeading(title = it) }
                    step.content(this)
                }
                Spacer(Modifier.height(SectionGap))
                StepFooter(
                    requestError = requestError,
                    showPrevious = current > 0,
                    onPrevious = { index = current - 1 },
                    nextLabel = if (isLast) submitLabel else "다음",
                    // 이 단계를 채워야 다음으로 간다. 뒤에서 한꺼번에 돌려보내면 어느 화면의 어느
                    // 칸이 문제였는지 되짚어야 한다.
                    nextEnabled = if (isLast) canSubmit else step.missing.isEmpty(),
                    isSubmitting = isSubmitting,
                    loadingDescription = loadingDescription,
                    onNext = { if (isLast) onSubmit() else index = current + 1 },
                )
            }
        }
    }
}

/**
 * 카드 맨 위에 놓이는 단계 제목입니다.
 *
 * 제목만 덩그러니 두면 그게 이 화면의 이름인지 카드의 이름인지 애매하다. 위에 짧은 막대를 얹어
 * 두면 글자가 아니라 한 단계를 여는 표제로 읽힌다.
 */
@Composable
private fun StepHeading(title: String) {
    // 높이를 잡아 두고 그 안에서 가운데로 맞춘다. 한 줄이 빠지고 늘 때마다 카드 크기가 따라
    // 움직이면, 단계를 오갈 때 화면이 위아래로 흔들린다.
    Column(
        modifier = Modifier.fillMaxWidth().height(StepHeadingHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 어디쯤 왔는지 보여 주는 줄입니다.
 *
 * 단계마다 막대 한 칸과 이름을 둔다. 막대만으로는 남은 칸 수만 알 뿐 다음에 뭘 묻는지 모른다.
 *
 * 지나온 단계는 눌러서 바로 돌아갈 수 있다 — 세 번째 단계에서 사진을 한 장 더 넣으려고 `이전` 을
 * 두 번 누르게 하지 않으려는 것이다. 아직 안 간 단계는 눌리지 않는다. 앞 단계를 채워야 다음으로
 * 가는데 여기서만 건너뛸 수 있으면 `다음` 을 잠가 둔 뜻이 없어진다.
 */
@Composable
private fun StepProgress(
    steps: List<PostCreateStep>,
    current: Int,
    onStepClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { stepIndex, step ->
            val isDone = stepIndex <= current
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .semantics {
                            contentDescription =
                                "${stepIndex + 1}단계 ${step.name}" + if (stepIndex == current) " (현재)" else ""
                        }
                        .clickable(enabled = stepIndex < current) { onStepClick(stepIndex) }
                        .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ProgressBarHeight)
                        .clip(CircleShape)
                        .background(
                            if (isDone) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ),
                )
                Text(
                    text = step.name,
                    color =
                        if (stepIndex == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    fontWeight = if (stepIndex == current) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 카드 바로 밑에 붙는 이동·등록 줄입니다.
 *
 * 화면 바닥에 고정하지 않는다. 고정하면 짧은 단계에서는 카드와 단추가 화면 양끝으로 갈라지고,
 * 단계마다 단추 자리가 달라 보인다. 카드에 붙여 두면 네 단계가 같은 자리에서 끝난다.
 */
@Composable
private fun StepFooter(
    requestError: String?,
    showPrevious: Boolean,
    onPrevious: () -> Unit,
    nextLabel: String,
    nextEnabled: Boolean,
    isSubmitting: Boolean,
    loadingDescription: String,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FieldGap),
    ) {
        requestError?.let { PostCreateErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showPrevious) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = SubmitShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    onClick = onPrevious,
                ) {
                    Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "이전",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Surface(
                // 되돌아가는 단추보다 넘어가는 단추를 넓게 잡아 어느 쪽이 앞으로 가는 길인지 보인다.
                modifier = Modifier.weight(if (showPrevious) 1.9f else 1f),
                shape = SubmitShape,
                // 잠긴 단추는 면을 바탕과 거의 같은 색으로 눌러 두고 테두리로만 자리를 남긴다.
                // 눌러 둔 면에 또렷한 글씨를 얹으면 "누를 수 있는데 색만 연한 단추"로 보인다.
                color =
                    if (nextEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                border =
                    if (nextEnabled) {
                        null
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    },
                enabled = nextEnabled && !isSubmitting,
                onClick = onNext,
            ) {
                Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    if (isSubmitting) {
                        MeonggoLoadingIndicator(
                            contentDescription = loadingDescription,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Text(
                            text = nextLabel,
                            color =
                                if (nextEnabled) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    // Material 이 정한 잠김 글자 농도(38%). 읽히기는 하되 살아 있는
                                    // 글씨로는 안 보이는 선이다.
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 입력 한 묶음입니다.
 *
 * 묶음마다 흰 면에 올려 바탕에서 띄운다. 예전에는 폼 전체가 한 장이고 묶음은 제목 글씨로만
 * 갈려서, 스크롤 중에 지금 어느 묶음을 채우는지 알기 어려웠다. 목록 카드와 같은 둥글기·그림자를
 * 쓰므로 목록에서 넘어와도 같은 앱으로 읽힌다.
 *
 * 번호(1. 2. 3.)는 뗐다 — 순서대로만 채워야 하는 폼이 아니고, 앱 어디에도 번호 매긴 제목이 없다.
 */
@Composable
fun PostCreateSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    suffix: String? = null,
    bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().meonggoFloatingShadow(),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxHeight().padding(SectionPadding)) {
            Column(
                // 카드 높이는 화면이 정하므로, 내용이 그보다 길면 이 안에서 굴린다.
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FieldGap),
            ) {
                // 제목을 안에서 직접 쓰는 단계(사진)는 여기서 한 번 더 달지 않는다.
                if (title != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        suffix?.let {
                            Text(
                                text = " $it",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                content()
            }
            // 카드 바닥에 붙는 자리. 굴러가는 부분 밖에 두므로 내용이 길어져도 늘 아래에 있다.
            bottomContent?.let {
                Spacer(Modifier.height(SectionGap))
                it()
            }
        }
    }
}

/** 칸 이름입니다. 칸과 붙여 읽히도록 위쪽 여백을 조금 더 준다. */
@Composable
fun PostCreateFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 글자를 받는 칸입니다.
 *
 * 흰 묶음 위에 흰 칸을 올리면 어디가 칸인지 안 보이므로 테두리로 가른다. 둥글기는 선택지
 * 버튼(14dp)과 맞췄다.
 */
@Composable
fun PostCreateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onLeave: (() -> Unit)? = null,
    singleLine: Boolean = true,
) {
    val field = rememberFormattedTextFieldState(value)
    OutlinedTextField(
        value = field.value,
        onValueChange = {
            field.value = it
            onValueChange(it.text)
        },
        modifier = modifier.fillMaxWidth().onLeaveFocus(onLeave),
        placeholder = {
            Text(
                text = placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = PostCreateFieldFontSize),
            )
        },
        isError = errorMessage != null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        shape = FieldShape,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = PostCreateFieldFontSize),
        // 여러 줄 칸은 엔터가 줄 바꿈이어야 하므로 "다음" 동작을 붙이지 않는다.
        keyboardOptions =
            KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
            ),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
            ),
    )
    errorMessage?.let { PostCreateErrorText(it) }
}

/**
 * 사진을 고르는 단계의 내용입니다.
 *
 * 입구를 하나로 두고, 앨범인지 촬영인지는 누른 뒤 시트에서 고른다. 두 칸을 나란히 두면 화면이
 * 가장 먼저 묻는 게 "무엇을 올릴까"가 아니라 "어디서 가져올까"가 된다.
 * [onTakePhotoClick] 이 없는 화면(실종 등록)은 고를 게 하나뿐이라 시트 없이 바로 앨범을 연다.
 *
 * 빈 자리는 점선으로 그린다. 입력칸과 선택지가 이미 실선을 쓰고 있어서, 같은 실선으로 그리면
 * 아직 채울 자리인지 이미 채운 자리인지 구별이 안 된다.
 *
 * 장수를 채우면 더하는 자리를 감춘다 — 앨범이나 카메라를 열었는데 고른 사진이 버려지지 않게.
 */
@Composable
fun PostCreatePhotoPicker(
    photoUris: List<String>,
    maxCount: Int,
    onAddPhotosClick: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
    onTakePhotoClick: (() -> Unit)? = null,
) {
    var isSourceSheetOpen by rememberSaveable { mutableStateOf(false) }
    val canAddMore = photoUris.size < maxCount
    val addPhoto = { if (onTakePhotoClick == null) onAddPhotosClick() else isSourceSheetOpen = true }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhotoStepGap),
    ) {
        if (canAddMore) {
            PhotoDropArea(onClick = addPhoto)
        }
        Text(
            text = "최대 ${maxCount}장까지 등록할 수 있어요.",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
        )
        PostCreatePhotoStrip(
            photoUris = photoUris,
            maxCount = maxCount,
            onAddClick = addPhoto,
            onRemovePhoto = onRemovePhoto,
        )
    }

    if (isSourceSheetOpen && onTakePhotoClick != null) {
        PhotoSourceSheet(
            onDismiss = { isSourceSheetOpen = false },
            onPickFromAlbum = {
                isSourceSheetOpen = false
                onAddPhotosClick()
            },
            onTakePhoto = {
                isSourceSheetOpen = false
                onTakePhotoClick()
            },
        )
    }
}

/**
 * 사진을 더하는 큰 자리입니다.
 *
 * 높이를 잡아 두고 안에서 가운데로 맞춘다. 글이 한 줄 늘고 줄 때마다 이 자리가 커졌다 작아지면
 * 아래 있는 것들이 따라 움직인다.
 */
@Composable
private fun PhotoDropArea(onClick: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(DropAreaHeight)
                .clip(DropAreaShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .meonggoDashedBorder(
                    color = MaterialTheme.colorScheme.primary,
                    cornerRadius = DropAreaRadius,
                )
                .semantics { contentDescription = "사진 추가하기" }
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PlusIcon(color = MaterialTheme.colorScheme.primary, iconSize = 42.dp, strokeWidth = 2.6f)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "사진 추가하기",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 고른 사진과 남은 자리를 함께 보여 주는 줄입니다.
 *
 * 빈 자리를 세 칸까지 깔아 둔다. 한 장도 없을 때 줄이 통째로 비면 사진을 여러 장 올릴 수 있다는
 * 게 `최대 10장` 이라는 글씨로만 남는다. 열 칸을 다 깔지는 않는다 — 아직 아무것도 안 한 화면에
 * 빈 칸만 열 개가 늘어선다.
 *
 * 세 칸으로 잡은 건 크기 때문이다. 다섯 칸일 때는 한 칸이 60dp 라 올린 사진이 무엇인지 알아볼
 * 수 없었다. 넉 장째부터는 옆으로 밀어서 본다 — 다음 칸이 오른쪽 끝에 조금 걸쳐 보이는 건
 * 그래서다. 딱 세 칸으로 끊으면 줄이 거기서 끝난 것처럼 보여 더 있는 줄 모른다.
 */
@Composable
fun PostCreatePhotoStrip(
    photoUris: List<String>,
    maxCount: Int,
    onAddClick: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    val emptySlots = (maxCount - photoUris.size).coerceAtMost(VISIBLE_EMPTY_SLOTS)
    val listState = rememberLazyListState()
    // 사진을 더해도 줄을 옮기지 않는다 — 더한 사진은 뒤에 붙고, 보던 자리는 그대로 둔다.
    //
    // 새 사진 쪽으로 줄을 옮겨 봤더니 이미 있던 사진들이 왼쪽 밖으로 밀려서, 한 장을 더했을 뿐인데
    // 그 사진이 맨 앞으로 온 것처럼 보였다(2026-09-24 사용 중 발견). 뒤에 붙은 게 화면 밖이어도
    // 넷째 칸이 오른쪽 끝에 걸쳐 있어 더 있다는 건 보인다.
    //
    // 첫 장만은 예외다. 한 장도 없을 때 줄은 첫 빈 칸을 붙들고 있는데, 사진이 그 앞에 끼어들면
    // 줄이 빈 칸을 제자리에 두려고 새 사진을 왼쪽 밖으로 밀어낸다 — 아무것도 안 들어간 것처럼 보인다.
    var lastCount by rememberSaveable { mutableIntStateOf(photoUris.size) }
    LaunchedEffect(photoUris.size) {
        if (lastCount == 0 && photoUris.isNotEmpty()) listState.scrollToItem(0)
        lastCount = photoUris.size
    }
    // 칸 크기는 줄 너비에서 나눠 잡는다. 고정값으로 두면 기기 폭에 따라 마지막 칸이 잘려 반쯤
    // 보인다. 바깥에서 `BoxWithConstraints` 로 재면 사진을 더해도 줄이 다시 그려지지 않아 새
    // 사진이 안 나타났다 — 그래서 줄 안에서 비율로 잡는다.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(PhotoSlotGap),
    ) {
        items(photoUris, key = { it }) { uri ->
            PhotoSlot(
                uri = uri,
                modifier = Modifier.fillParentMaxWidth(SLOT_WIDTH_FRACTION).aspectRatio(1f),
                onRemovePhoto = onRemovePhoto,
            )
        }
        items(emptySlots, key = { "empty-$it" }) {
            EmptyPhotoSlot(
                modifier = Modifier.fillParentMaxWidth(SLOT_WIDTH_FRACTION).aspectRatio(1f),
                onClick = onAddClick,
            )
        }
    }
}

/** 아직 사진이 없는 자리입니다. */
@Composable
private fun EmptyPhotoSlot(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(PhotoSlotShape)
                .meonggoDashedBorder(
                    color = MaterialTheme.colorScheme.outline,
                    cornerRadius = PhotoSlotRadius,
                )
                .semantics { contentDescription = "사진 추가" }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlusIcon(color = MaterialTheme.colorScheme.outline, iconSize = 20.dp, strokeWidth = 1.8f)
    }
}

/** 어떤 사진이 매칭에 도움이 되는지 알려 주는 상자입니다. 카드 맨 아래에 붙여 둔다. */
@Composable
fun PostCreatePhotoTips() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TipBoxShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "더 좋은 사진을 위한 팁!",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        PHOTO_TIPS.forEach { tip ->
            Text(
                text = "• $tip",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            )
        }
    }
}

private val PHOTO_TIPS =
    listOf(
        "밝은 곳에서 촬영해 주세요.",
        "아이의 얼굴이 잘 보이게 찍어주세요.",
        "가능하면 전신이 보이는 사진도 함께 올려주세요.",
    )

private const val VISIBLE_EMPTY_SLOTS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSourceSheet(
    onDismiss: () -> Unit,
    onPickFromAlbum: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = SheetGutter)
                    .padding(bottom = SheetGutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "사진 가져오기",
                modifier = Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 나란히 둔다. 위아래로 쌓으면 위의 것이 먼저 권하는 길처럼 보이는데, 둘은 그냥
            // 다른 길일 뿐이다.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhotoSourceOption(
                    label = "앨범에서 가져오기",
                    onClick = onPickFromAlbum,
                    modifier = Modifier.weight(1f),
                )
                PhotoSourceOption(
                    label = "촬영하기",
                    onClick = onTakePhoto,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PhotoSourceOption(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onClick,
    ) {
        Box(
            Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** 고른 사진 한 장입니다. 지우는 단추는 사진 위 어떤 색에서도 읽히도록 어두운 원에 올린다. */
@Composable
private fun PhotoSlot(
    uri: String,
    onRemovePhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(PhotoSlotShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "선택한 사진",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Surface(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .semantics { contentDescription = "사진 빼기" }
                    .clickable { onRemovePhoto(uri) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "×",
                    modifier = Modifier.padding(bottom = 2.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 동의 한 줄입니다. 줄 전체가 체크를 토글하므로 작은 네모를 정확히 겨눌 필요가 없다. */
@Composable
fun PostCreateConsentRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                // 네모를 칸 테두리와 같은 선에 두면 칸보다 왼쪽으로 밀려 보인다. 칸은 테두리가
                // 있고 네모는 없어서, 같은 좌표라도 눈에는 네모가 더 나가 있다.
                .padding(start = ConsentRowStartInset, top = ConsentRowTopInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        )
    }
}

/**
 * 사진 칸이 막힌 이유를 창으로 알립니다.
 *
 * 카드 안에 빨간 한 줄로 끼워 넣으면 그 줄이 생겼다 없어질 때마다 위아래가 밀려서, 가만히 있던
 * 화면이 움직이고 카드가 스크롤로 바뀐다. 사진 단계는 자리가 빠듯해 그 흔들림이 특히 크다.
 * 게다가 이 줄은 다른 단계에서 등록을 눌렀을 때도 여기 뜨기 때문에, 정작 사용자가 보고 있는
 * 화면에서는 아무 일도 안 일어난 것처럼 보였다(2026-09-23 사용 중 발견).
 */
@Composable
fun PostCreateNoticeDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "사진을 확인해 주세요",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인", fontWeight = FontWeight.Bold)
            }
        },
    )
}

/**
 * 넣지 못한 사진이 있으면 이유를 알려 주는 창.
 *
 * 사진 줄 아래 빨간 한 줄만으로는 눈에 띄지 않고 이유도 하나만 보였다(2026-09-23 QA). 몇 장 중 몇 장이
 * 빠졌는지 제목에, 이유는 묶어서 본문에, 등록할 수 있는 사진의 기준은 늘 마지막 줄에 적는다 — 사용자가 할 수
 * 있는 일은 다른 사진을 고르는 것이라 어떤 사진이 되는지를 알아야 한다.
 */

@Composable
fun PhotoRejectionDialog(
    notice: PhotoRejectionNotice,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text =
                    if (notice.rejectedCount >= notice.pickedCount) {
                        "사진을 넣지 못했어요"
                    } else {
                        "사진 ${notice.pickedCount}장 중 ${notice.rejectedCount}장을 넣지 못했어요"
                    },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notice.lines.forEach { line ->
                    Text(text = "• $line", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = PHOTO_REQUIREMENTS,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { MeonggoButton(text = "확인", onClick = onDismiss) },
    )
}

@Composable
fun PostCreateErrorText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

private val StepHeadingHeight = 96.dp
private val ConsentRowStartInset = 8.dp
private val ConsentRowTopInset = 8.dp
private val ProgressBarHeight = 9.dp
private val SectionGap = 14.dp
private val SectionPadding = 18.dp
private val FieldGap = 8.dp
private val ScrollBottomInset = 28.dp
private val FieldShape = RoundedCornerShape(14.dp)
private val SubmitShape = RoundedCornerShape(18.dp)
private val PhotoSlotGap = 10.dp
private val PhotoStepGap = 18.dp

// 세 칸이 다 들어가고 넷째 칸의 왼쪽 끝이 조금 남는 너비. 0.3 이면 딱 세 칸에서 끊겨 더 있는 줄 몰랐다.
private const val SLOT_WIDTH_FRACTION = 0.28f
private val PhotoSlotShape = RoundedCornerShape(16.dp)
private val PhotoSlotRadius = 16.dp
private val DropAreaRadius = 20.dp
private val DropAreaHeight = 168.dp
private val DropAreaShape = RoundedCornerShape(20.dp)
private val TipBoxShape = RoundedCornerShape(16.dp)
private val SheetRadius = 28.dp
private val SheetGutter = 20.dp

@Preview(showBackground = true, widthDp = 411, heightDp = 800, backgroundColor = 0xFFFDFBF7)
@Composable
private fun PostCreateWizardPreview() {
    MeonggoBanjeomTheme {
        PostCreateWizard(
            title = "실종동물 등록",
            steps =
                listOf(
                    PostCreateStep(
                        name = "사진",
                        missing = listOf("사진"),
                    ) {
                        PostCreatePhotoPicker(
                            photoUris = emptyList(),
                            maxCount = 10,
                            onAddPhotosClick = {},
                            onRemovePhoto = {},
                            onTakePhotoClick = {},
                        )
                    },
                    PostCreateStep(name = "기본 정보", heading = "기본 정보") {
                        PostCreateFieldLabel("이름 (선택)")
                        PostCreateTextField("", {}, "이름을 입력하세요")
                    },
                    PostCreateStep(name = "실종 정보", heading = "실종 정보", missing = listOf("실종 날짜")) {
                        PostCreateFieldLabel("실종 장소")
                        PostCreateTextField("", {}, "실종 장소를 입력하세요")
                    },
                ),
            onExit = {},
            submitLabel = "등록하기",
            canSubmit = false,
            isSubmitting = false,
            onSubmit = {},
            loadingDescription = "등록 요청 중",
        )
    }
}

/**
 * 등록 폼의 입력 글자 크기입니다.
 *
 * 본문 기본값(14sp)은 56dp 짜리 칸 안에서 작아 보였다. 칸·날짜·시간·지역이 모두 이 값을 쓰므로
 * 한 군데만 고쳐도 폼 전체가 같이 움직인다.
 */
internal val PostCreateFieldFontSize = 15.sp
