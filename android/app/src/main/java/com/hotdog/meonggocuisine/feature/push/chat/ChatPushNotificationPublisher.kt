package com.hotdog.meonggocuisine.feature.push.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hotdog.meonggocuisine.MainActivity
import com.hotdog.meonggocuisine.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface ChatPushNotificationPublisher {
    fun show(message: ChatPushMessage)
}

@Singleton
class AndroidChatPushNotificationPublisher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ChatPushNotificationPublisher {
        override fun show(message: ChatPushMessage) {
            if (!canPostNotifications()) return
            createChannel()
            val openChatIntent =
                Intent(context, MainActivity::class.java).apply {
                    action = ChatPushContract.ACTION_OPEN_CHAT
                    putExtra(ChatPushContract.EXTRA_CHAT_ROOM_ID, message.chatRoomId)
                    putExtra(ChatPushContract.EXTRA_MESSAGE_ID, message.messageId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    message.messageId.hashCode(),
                    openChatIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("새 채팅 메시지")
                    .setContentText("새 메시지가 도착했어요.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(message.messageId.hashCode(), notification)
            } catch (_: SecurityException) {
                // 사용자가 설정 화면에서 권한을 철회하는 순간과 겹쳐도 채팅 기능은 계속 동작해야 한다.
            }
        }

        private fun canPostNotifications(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun createChannel() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "채팅 메시지",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "새 채팅 메시지 도착 알림"
                }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private companion object {
            const val CHANNEL_ID = "chat_messages"
        }
    }
