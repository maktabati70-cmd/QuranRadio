package com.quranradio.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * إشعارات من المطور لكل المستخدمين (Firebase Cloud Messaging).
 * تُرسل من لوحة Firebase: Messaging ← New campaign ← Notifications.
 * يمكن إضافة رابط في «Custom data» بالمفتاح url ليُفتح عند الضغط على الإشعار.
 */
class MessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        // يصل هنا والتطبيق مفتوح (وهو مغلق يعرضه النظام تلقائياً)
        val title = m.notification?.title ?: m.data["title"] ?: getString(R.string.app_name)
        val body = m.notification?.body ?: m.data["body"] ?: return
        Notify.show(this, title, body, m.data["url"])
    }

    override fun onNewToken(token: String) { Notify.subscribe() }
}

object Notify {
    const val CHANNEL = "news"

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "التنبيهات والأخبار", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "رسائل المطور: تحديثات، إذاعات جديدة، تذكير بالمناسبات" })
        }
    }

    fun subscribe() {
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic("all") }
    }

    fun show(ctx: Context, title: String, body: String, url: String?) {
        channel(ctx)
        val open = if (!url.isNullOrBlank()) Intent(Intent.ACTION_VIEW, Uri.parse(url))
                   else Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, (System.currentTimeMillis() % 100000).toInt(), open, flags)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFD9B46C.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n) }
    }
}
