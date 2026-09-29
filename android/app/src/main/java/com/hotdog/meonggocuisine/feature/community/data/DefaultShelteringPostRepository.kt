package com.hotdog.meonggocuisine.feature.community.data

import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

class DefaultShelteringPostRepository
    @Inject
    constructor(
        private val communityApi: CommunityApi,
    ) : ShelteringPostRepository {
        override suspend fun getShelteringPosts(
            breedName: String?,
            regionCode: String?,
            filter: PostListFilter,
            cursor: String?,
        ): ShelteringPostListResult =
            try {
                val response =
                    communityApi.getPosts(
                        type = "SHELTERING",
                        species = filter.species.apiValue,
                        sex = filter.sex.apiValue,
                        breedName = breedName?.trim()?.takeIf(String::isNotEmpty),
                        regionCode = regionCode,
                        // LATEST 는 서버 기본값이라 보내지 않는다 — sort 를 모르는 옛 서버(v1.3.0)가 400 을 주기 때문(2026-09-22).
                        sort = filter.sort.takeIf { it != PostListSort.LATEST }?.name,
                        cursor = cursor,
                    )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    ShelteringPostListResult.Success(
                        posts = body.data.items,
                        hasNext = body.data.page.hasNext,
                        nextCursor = body.data.page.nextCursor,
                    )
                } else {
                    ShelteringPostListResult.Failure("보호동물 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                ShelteringPostListResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.")
            } catch (_: Exception) {
                ShelteringPostListResult.Failure("보호동물 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
            }
    }
