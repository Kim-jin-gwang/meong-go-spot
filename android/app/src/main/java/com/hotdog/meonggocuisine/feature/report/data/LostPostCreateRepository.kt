package com.hotdog.meonggocuisine.feature.report.data

data class LostPostCreateInput(
    val clientRequestId: String,
    val name: String,
    val species: String,
    val breedName: String,
    val sex: String,
    val color: String,
    val eventDate: String,
    val eventTime: String,
    val eventPlace: String,
    val regionCode: String,
    val exactLocationVisible: Boolean,
    val featureText: String,
    val photoUris: List<String>,
)

sealed interface LostPostCreateResult {
    data class Success(val postId: Long) : LostPostCreateResult

    data class InvalidInput(
        val message: String,
        val fieldErrors: Map<String, String>,
    ) : LostPostCreateResult

    data class PhotoInvalid(val message: String) : LostPostCreateResult

    data class Failure(val message: String) : LostPostCreateResult
}

interface LostPostCreateRepository {
    suspend fun createLostPost(input: LostPostCreateInput): LostPostCreateResult
}
