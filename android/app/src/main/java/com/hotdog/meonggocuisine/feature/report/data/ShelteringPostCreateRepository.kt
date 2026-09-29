package com.hotdog.meonggocuisine.feature.report.data

data class ShelteringPostCreateInput(
    val clientRequestId: String,
    val name: String,
    val species: String,
    val breedName: String,
    val sex: String,
    val color: String,
    val foundDate: String,
    val foundTime: String,
    val foundPlace: String,
    val foundLocationVisible: Boolean,
    val protectionStatus: String,
    val currentProtectionPlace: String,
    val currentLocationVisible: Boolean,
    val regionCode: String,
    val featureText: String,
    val photoUris: List<String>,
)

sealed interface ShelteringPostCreateResult {
    data class Success(val postId: Long) : ShelteringPostCreateResult

    data class InvalidInput(
        val message: String,
        val fieldErrors: Map<String, String>,
    ) : ShelteringPostCreateResult

    data class PhotoInvalid(val message: String) : ShelteringPostCreateResult

    data class Failure(val message: String) : ShelteringPostCreateResult
}

interface ShelteringPostCreateRepository {
    suspend fun createShelteringPost(input: ShelteringPostCreateInput): ShelteringPostCreateResult
}
