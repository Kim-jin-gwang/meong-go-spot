package com.hotdog.meonggocuisine.core.navigation

import kotlinx.serialization.Serializable

/** 모든 앱 화면 경로가 구현하는 공통 타입입니다. */
sealed interface AppRoute

/** 로그인하지 않은 사용자도 진입할 수 있는 화면 경로입니다. */
sealed interface PublicRoute : AppRoute

/** 로그인 여부를 확인한 뒤 진입해야 하는 화면 경로입니다. */
sealed interface AuthRequiredRoute : AppRoute

@Serializable
data object HomeRoute : PublicRoute

/** 보호소 소식(카드 5장) — 홈의 소식 띠에서 들어온다. 비로그인도 본다. */
@Serializable
data object InsightsRoute : PublicRoute
