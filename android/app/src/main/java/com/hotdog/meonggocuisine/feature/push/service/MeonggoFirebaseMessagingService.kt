package com.hotdog.meonggocuisine.feature.push.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hotdog.meonggocuisine.feature.push.chat.ChatPushMessageHandler
import com.hotdog.meonggocuisine.feature.push.data.PushDeviceLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MeonggoFirebaseMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var pushDeviceLifecycle: PushDeviceLifecycle

    @Inject
    lateinit var chatPushMessageHandler: ChatPushMessageHandler

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        pushDeviceLifecycle.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        chatPushMessageHandler.handle(message.data)
    }
}
