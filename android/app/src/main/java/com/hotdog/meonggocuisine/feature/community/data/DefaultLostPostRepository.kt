package com.hotdog.meonggocuisine.feature.community.data

import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

class DefaultLostPostRepository
    @Inject
    constructor(
        private val communityApi: CommunityApi,
    ) : LostPostRepository {
        override suspend fun getLostPosts(
            breedName: String?,
            regionCode: String?,
            filter: PostListFilter,
            cursor: String?,
        ): LostPostListResult =
            try {
                val response =
                    communityApi.getPosts(
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
                    LostPostListResult.Success(
                        posts = body.data.items,
                        hasNext = body.data.page.hasNext,
                        nextCursor = body.data.page.nextCursor,
                    )
                } else {
                    LostPostListResult.Failure("게시물 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                LostPostListResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.")
            } catch (_: Exception) {
                LostPostListResult.Failure("게시물 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
            }
    }
