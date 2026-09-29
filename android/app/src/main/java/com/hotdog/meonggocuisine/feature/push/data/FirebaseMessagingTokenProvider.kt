package com.hotdog.meonggocuisine.feature.push.data

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

interface PushTokenProvider {
    suspend fun currentToken(): String
}

class FirebaseMessagingTokenProvider
    @Inject
    constructor() : PushTokenProvider {
        override suspend fun currentToken(): String = FirebaseMessaging.getInstance().token.await()
    }
