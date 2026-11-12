package com.quranradio.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        const val GH_REPO = "maktabati70-cmd/QuranRadio"
        /** تطبيقات راديو FM المدمجة المعروفة لدى الشركات المصنّعة */
        val FM_PACKAGES = listOf(
            "com.sec.android.app.fm", "com.android.fmradio", "com.caf.fmradio", "com.miui.fm", "com.miui.fmradio",
            "com.huawei.android.FMRadio", "com.motorola.fmplayer", "com.lge.fmradio", "com.sonyericsson.fmradio",
            "com.sonymobile.fmradio", "com.htc.fm", "com.asus.fmradio", "com.android.fm", "com.coloros.fmradio",
            "com.oppo.fmradio", "com.vivo.fmradio", "com.zte.fmradio", "com.qualcomm.fmradio", "com.mediatek.fmradio",
            "com.transsion.fmradio", "com.infinix.fmradio", "com.tecno.fmradio", "com.nokia.fmradio",
            "com.realme.fmradio", "com.oneplus.fmradio"
        )
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
            allowFileAccess = true
            javaScriptEnabled = true
            textZoom = 100 // تجاهل تكبير خط النظام حتى لا تخرج الواجهة عن الشاشة
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            // روابط خارجية (واتساب للتواصل مع المطور) تُفتح في تطبيقاتها
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "file") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else loadBestUi()
        web.postDelayed({ checkGithubUpdate() }, 4000)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = send("back")
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 0
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        Notify.channel(this); Notify.subscribe() // إشعارات المطور لكل المستخدمين
        openPushLink(intent)
    }

    // إشعار فيه رابط (المفتاح url) وصل والتطبيق مغلق: نفتح الرابط عند الضغط عليه
    private fun openPushLink(i: Intent?) {
        val u = i?.getStringExtra("url") ?: return
        i.removeExtra("url")
        if (u.startsWith("http")) runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openPushLink(intent)
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

    /** إغلاق نهائي: إيقاف البث والتلاوة والخدمة، وإزالة التطبيق من الخلفية */
    private fun closeApp() {
        runCatching { controller?.run { stop(); clearMediaItems() } }
        runCatching { web.evaluateJavascript("try{QA.audio.pause()}catch(e){}", null) }
        stopCompassSensor()
        runCatching { stopService(Intent(this, PlaybackService::class.java)) }
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (isFinishing) runCatching { stopService(Intent(this, PlaybackService::class.java)) }
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

    private fun findFmApp(): String? {
        val pm = packageManager
        FM_PACKAGES.firstOrNull { pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        // بحث احتياطي: أي تطبيق مثبّت يحمل اسماً يدل على راديو FM
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        return apps.firstOrNull { ri ->
            val p = ri.activityInfo.packageName.lowercase()
            val label = ri.loadLabel(pm).toString().lowercase()
            p != packageName && (p.contains("fmradio") || p.endsWith(".fm") || label.contains("fm radio") ||
                label.contains("راديو fm") || label == "fm" || label.contains("radio fm"))
        }?.activityInfo?.packageName
    }

    /** بوصلة القبلة: حساس الاتجاه يرسل زاوية الشمال إلى الواجهة */
    private var compassOn = false
    private val compass = object : SensorEventListener {
        private val rot = FloatArray(9); private val ori = FloatArray(3); private var last = 0L
        override fun onSensorChanged(e: SensorEvent) {
            val now = System.currentTimeMillis(); if (now - last < 80) return; last = now
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, ori)
            val deg = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360)
            web.evaluateJavascript("window.nativeHeading && window.nativeHeading(" + "%.1f".format(java.util.Locale.US, deg) + ")", null)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    private fun startCompassSensor() {
        if (compassOn) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) ?: return
        compassOn = sm.registerListener(compass, s, SensorManager.SENSOR_DELAY_UI)
    }
    private fun stopCompassSensor() {
        if (!compassOn) return
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(compass); compassOn = false
    }

    override fun onPause() { stopCompassSensor(); super.onPause() }

    /**
     * التحديث من GitHub:
     * 1) يقرأ آخر إصدار منشور (Release). إذا كان رقمه أكبر من رقم التطبيق المثبّت يعرض «تحديث متوفر» مع رابط ملف APK.
     * 2) يحمّل ملف radios.json من نفس الإصدار ويحفظه، فتظهر الإذاعات الجديدة عند كل المستخدمين بدون تثبيت جديد.
     */
    private fun checkGithubUpdate() = Thread {
        runCatching {
            val api = java.net.URL("https://api.github.com/repos/$GH_REPO/releases/latest")
            val c = api.openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "AlmuslimRadio")
            if (c.responseCode != 200) return@runCatching
            val j = org.json.JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name").filter { it.isDigit() }.toLongOrNull() ?: return@runCatching
            val name = j.optString("name")
            val notes = j.optString("body").take(300)
            var apk = ""; var radios = ""; var ui = ""
            val assets = j.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i); val n = a.optString("name"); val u = a.optString("browser_download_url")
                if (n.endsWith(".apk")) apk = u
                if (n == "radios.json") radios = u
                if (n == "radio.html") ui = u
            }
            if (ui.isNotEmpty()) runCatching {
                val html = java.net.URL(ui).openStream().bufferedReader(Charsets.UTF_8).readText()
                val f = java.io.File(filesDir, "remote_ui.html")
                val cur = if (f.exists()) meta(f.readText(), "ui-version") else 0L
                val assetV = runCatching { meta(this@MainActivity.assets.open("radio.html").bufferedReader().readText(), "ui-version") }.getOrDefault(0L)
                val v = meta(html, "ui-version")
                if (html.length > 50000 && v > maxOf(cur, assetV) && meta(html, "min-native") <= myVersionCode()) {
                    f.writeText(html)
                    runOnUiThread { web.evaluateJavascript("window.uiUpdated && window.uiUpdated()", null) }
                }
            }
            if (radios.isNotEmpty()) {
                val txt = java.net.URL(radios).openStream().bufferedReader(Charsets.UTF_8).readText()
                val d = org.json.JSONObject(txt)
                val count = d.getJSONArray("stations").length()
                val f = java.io.File(filesDir, "remote_radios.json")
                val old = if (f.exists()) f.readText() else ""
                if (count > 50 && txt != old) {
                    f.writeText(txt)
                    if (old.isNotEmpty()) runOnUiThread { web.evaluateJavascript("window.stationsUpdated && window.stationsUpdated($count)", null) }
                }
            }
            @Suppress("DEPRECATION")
            val mine = packageManager.getPackageInfo(packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
            }
            if (tag > mine && apk.isNotEmpty()) {
                val q = org.json.JSONObject.quote(name.ifEmpty { "v$tag" })
                val n = org.json.JSONObject.quote(notes)
                val u = org.json.JSONObject.quote(apk)
                runOnUiThread { web.evaluateJavascript("window.updateAvailable && window.updateAvailable($q,$u,$n)", null) }
            }
        }
    }.start()


    // ===================== الواجهة المحدَّثة من GitHub =====================
    private fun meta(html: String, name: String): Long =
        Regex("<meta name=\"" + name + "\" content=\"(\\d+)\"").find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun myVersionCode(): Long {
        @Suppress("DEPRECATION")
        val pi = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    /** يحمّل الواجهة الأحدث: النسخة المحفوظة من GitHub إن كانت أحدث ومتوافقة، وإلا المدمجة في التطبيق */
    private fun loadBestUi() {
        val asset = runCatching { assets.open("radio.html").bufferedReader(Charsets.UTF_8).readText() }.getOrDefault("")
        val f = java.io.File(filesDir, "remote_ui.html")
        val remote = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        val useRemote = remote.length > 50000 && meta(remote, "ui-version") > meta(asset, "ui-version") &&
            meta(remote, "min-native") <= myVersionCode()
        if (useRemote) web.loadDataWithBaseURL("file:///android_asset/", remote, "text/html", "utf-8", null)
        else web.loadUrl("file:///android_asset/radio.html")
    }

    // ===================== التحديث بضغطة واحدة =====================
    private var pendingApk: java.io.File? = null

    private fun downloadAndInstall(url: String) = Thread {
        val ok = runCatching {
            val dir = java.io.File(cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = java.io.File(dir, "update.apk")
            var link = url
            var conn = java.net.URL(link).openConnection() as java.net.HttpURLConnection
            var hops = 0
            while (true) { // متابعة التحويلات (GitHub يحوّل إلى خادم الملفات)
                if (hops > 0) conn = java.net.URL(link).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true; conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "AlmuslimRadio")
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) { hops++; link = conn.getHeaderField("Location"); conn.disconnect(); continue }
                if (code != 200) error("HTTP $code")
                break
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -1; var n = 0
                while (input.read(buf).also { n = it } > 0) {
                    o.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; runOnUiThread { web.evaluateJavascript("window.updProgress&&window.updProgress($p)", null) } } }
                }
            } }
            pendingApk = out
            true
        }.getOrDefault(false)
        runOnUiThread {
            web.evaluateJavascript("window.updDone&&window.updDone($ok)", null)
            if (ok) installPending()
        }
    }.start()

    private fun installPending() {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            web.evaluateJavascript("window.updNeedPermission&&window.updNeedPermission()", null)
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            }
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
    }

    override fun onResume() {
        super.onResume()
        // بعد السماح بالتثبيت من الإعدادات: نكمل التثبيت تلقائياً
        if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) installPending()
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

        /** اسم حزمة تطبيق راديو FM المدمج في الهاتف، أو نص فارغ إن لم يوجد */
        @JavascriptInterface
        fun fmApp(): String = findFmApp() ?: ""

        /** هل السماعة السلكية موصولة؟ (سلكها هو هوائي FM) */
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun headsetPlugged(): Boolean {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT < 23) return am.isWiredHeadsetOn
            val wired = mutableSetOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            if (Build.VERSION.SDK_INT >= 26) wired += AudioDeviceInfo.TYPE_USB_HEADSET
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in wired }
        }

        /** فتح تطبيق راديو FM المدمج */
        @JavascriptInterface
        fun openFm(): Boolean {
            val pkg = findFmApp() ?: return false
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { startActivity(i); true }.getOrDefault(false)
        }

        /** حفظ بيانات (المصحف، آخر صفحة، حجم الخط) في ذاكرة التطبيق للقراءة بدون إنترنت */
        @JavascriptInterface
        fun saveData(key: String, value: String) {
            runCatching { java.io.File(filesDir, "data_" + key.replace(Regex("[^A-Za-z0-9_]"), "")).writeText(value) }
        }

        @JavascriptInterface
        fun loadData(key: String): String =
            runCatching { java.io.File(filesDir, "data_" + key.replace(Regex("[^A-Za-z0-9_]"), "")).readText() }.getOrDefault("")

        /** الموقع التقريبي لحساب أوقات الصلاة: "lat,lng" أو نص فارغ */
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun getLocation(): String {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 2) }
                return ""
            }
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = runCatching {
                lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            }.getOrNull() ?: return ""
            return "${loc.latitude},${loc.longitude}"
        }

        /** مستويات أعمدة الصوت الحقيقية (7 قيم من 0 إلى 1) */
        @JavascriptInterface
        fun levels(): String = AudioLevels.snapshot()

        /** لون شريط الحالة حسب الوضع الليلي */
        @JavascriptInterface
        fun setNight(on: Boolean) = runOnUiThread {
            val c = if (on) 0xFF01030A.toInt() else 0xFF070B16.toInt()
            window.statusBarColor = c; window.navigationBarColor = c; web.setBackgroundColor(c)
        }

        @JavascriptInterface
        fun startCompass() = runOnUiThread { startCompassSensor() }

        @JavascriptInterface
        fun stopCompass() = runOnUiThread { stopCompassSensor() }

        /** قائمة الإذاعات المحدَّثة المحفوظة من آخر إصدار على GitHub (أو نص فارغ) */
        @JavascriptInterface
        fun remoteStations(): String =
            runCatching { java.io.File(filesDir, "remote_radios.json").readText() }.getOrDefault("")

        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        }

        /** رقم الإصدار الظاهر في أسفل الشاشة */
        @JavascriptInterface
        fun appVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")

        @JavascriptInterface
        fun installUpdate(url: String) { downloadAndInstall(url) }

        @JavascriptInterface
        fun exit() = runOnUiThread { closeApp() }
    }
}
