package com.hotdog.meonggocuisine.core.navigation

import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute : PublicRoute

@Serializable
data object SignUpRoute : PublicRoute

/** 계정 찾기 — 로그인 화면의 "아이디·비밀번호 찾기" 에서 들어온다. 휴대전화 인증 → 아이디 확인 → (선택) 비밀번호 재설정. */
@Serializable
data object AccountRecoveryRoute : PublicRoute

/** 마이페이지 — 헤더의 사람 아이콘이 여는 화면. 내 게시물·닉네임·비밀번호·로그아웃·계정 삭제 입구다. */
@Serializable
data object MyPageRoute : AuthRequiredRoute

/** 소개팅에서 관심 표시한 보호 동물 목록. */
@Serializable
data class NicknameEditRoute(
    val currentNickname: String,
) : AuthRequiredRoute

@Serializable
data object PasswordChangeRoute : AuthRequiredRoute

@Serializable
data object WithdrawRoute : AuthRequiredRoute
