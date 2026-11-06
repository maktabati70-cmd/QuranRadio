package com.quranradio.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * الواجهة: صفحة HTML (الراديو الكلاسيكي) داخل WebView.
 * التشغيل: مشغّل ExoPlayer أصلي في خدمة خلفية — يستمر مع إطفاء الشاشة ويظهر في الإشعارات.
 * تطوير: أبو عبدالله الشامي
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private fun totalBytes(): Long {
            val uid = Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
            return (if (rx < 0) 0 else rx) + (if (tx < 0) 0 else tx)
        }
        /** نقطة البداية تُحفظ مرة واحدة عند تشغيل التطبيق */
        private val startBytes: Long by lazy { totalBytes() }
    }

    private lateinit var web: WebView
    private var future: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = future?.let { if (it.isDone && !it.isCancelled) runCatching { it.get() }.getOrNull() else null }
    private var pendingVolume = 0.8f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (startBytes < 0) return // تسجيل نقطة البداية عند الفتح
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF070B16.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("file:///android_asset/radio.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = send("back")
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 0
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = controller ?: return@addListener
            c.addListener(listener)
            c.volume = pendingVolume
            if (c.isPlaying) send("playing")
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val c = controller ?: return
            if (isPlaying) send("playing")
            else if (!c.playWhenReady && c.playbackState != Player.STATE_ENDED) send("pause")
        }
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> send("waiting")
                Player.STATE_ENDED -> send("ended")
            }
        }
        override fun onPlayerError(error: PlaybackException) = send("error")
    }

    private fun send(event: String) = runOnUiThread {
        web.evaluateJavascript("window.nativeEvent && window.nativeEvent('$event')", null)
    }

    /** دوال تستدعيها صفحة HTML عبر window.Android */
    inner class Bridge {
        @JavascriptInterface
        fun play(url: String, title: String, subtitle: String) = runOnUiThread {
            val c = controller ?: return@runOnUiThread
            val item = MediaItem.Builder()
                .setMediaId(url).setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(title).setArtist(subtitle)
                        .setAlbumTitle("مذياع أنا المسلم — تطوير أبو عبدالله الشامي").build()
                ).build()
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }

        @JavascriptInterface
        fun pause() = runOnUiThread { controller?.pause() }

        @JavascriptInterface
        fun stop() = runOnUiThread { controller?.run { stop(); clearMediaItems() } }

        @JavascriptInterface
        fun setVolume(v: Float) = runOnUiThread {
            pendingVolume = v.coerceIn(0f, 1f)
            controller?.volume = pendingVolume
        }

        /** نوع الاتصال الحالي: wifi أو cellular أو none */
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun netType(): String {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return if (Build.VERSION.SDK_INT >= 23) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "wifi"
                }
            } else {
                val info = cm.activeNetworkInfo ?: return "none"
                if (!info.isConnected) "none"
                else if (info.type == ConnectivityManager.TYPE_MOBILE) "cellular" else "wifi"
            }
        }

        /** عدد البايتات التي استهلكها التطبيق منذ فتحه */
        @JavascriptInterface
        fun usageBytes(): String = (totalBytes() - startBytes).coerceAtLeast(0).toString()

        @JavascriptInterface
        fun exit() = runOnUiThread { moveTaskToBack(true) }
    }
}
