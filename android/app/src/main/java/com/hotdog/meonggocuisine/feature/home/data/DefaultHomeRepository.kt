package com.hotdog.meonggocuisine.feature.home.data

import com.hotdog.meonggocuisine.feature.community.data.CommunityApi
import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import javax.inject.Inject

/**
 * 메인 화면 데이터를 모읍니다.
 *
 * 목록은 기존 P1 을 그대로 쓰므로 `CommunityApi` 를 재사용한다. 미리보기는 한 페이지(10건)에서
 * 앞부분만 잘라 쓰고 더 보기는 각 목록 화면이 담당한다.
 *
 * 조각 하나가 실패해도 메인 전체를 비우지 않기 위해 여기서 예외를 삼키고 비어 있는 결과를
 * 돌려준다. 인사이트는 조각별 null 과 호출 실패(전체 null)가 화면에서 같은 모양(준비 중 카드)이라 구분하지 않는다.
 */
class DefaultHomeRepository
    @Inject
    constructor(
        private val homeApi: HomeApi,
        private val communityApi: CommunityApi,
    ) : HomeRepository {
        override suspend fun getInsights(regionCode: String?): HomeInsights? =
            runCatching {
                val response = homeApi.getInsights(regionCode)
                response.body()?.takeIf { response.isSuccessful }?.data
            }.getOrNull()

        override suspend fun getShelteringPreview(regionCode: String?): List<LostPostSummary> =
            runCatching {
                val response = communityApi.getPosts(type = "SHELTERING", regionCode = regionCode)
                response.body()?.takeIf { response.isSuccessful }?.data?.items.orEmpty()
            }.getOrElse { emptyList() }.take(SHELTERING_PREVIEW_SIZE)

        override suspend fun getLostPreview(): List<LostPostSummary> =
            runCatching {
                val response = communityApi.getPosts(type = "LOST")
                response.body()?.takeIf { response.isSuccessful }?.data?.items.orEmpty()
            }.getOrElse { emptyList() }.take(LOST_PREVIEW_SIZE)

        private companion object {
            const val SHELTERING_PREVIEW_SIZE = 6
            const val LOST_PREVIEW_SIZE = 3
        }
    }
