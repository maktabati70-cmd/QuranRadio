package com.quranradio.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: StationAdapter
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.let { if (it.isDone && !it.isCancelled) runCatching { it.get() }.getOrNull() else null }

    private var currentCat = "all"
    private var query = ""
    private val prefs by lazy { getSharedPreferences("radio", Context.MODE_PRIVATE) }
    private val favs: MutableSet<String> by lazy { prefs.getStringSet("favs", emptySet())!!.toMutableSet() }

    private lateinit var player: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowStatus: TextView
    private lateinit var btnPlay: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Stations.load(this)

        player = findViewById(R.id.player)
        nowTitle = findViewById(R.id.nowTitle)
        nowStatus = findViewById(R.id.nowStatus)
        btnPlay = findViewById(R.id.btnPlay)

        adapter = StationAdapter()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(adapter.getItem(pos)) }

        setupChips()

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        btnPlay.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 0
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        refresh()
    }

    private fun setupChips() {
        val group = findViewById<ChipGroup>(R.id.chips)
        val entries = linkedMapOf("all" to "الكل", "fav" to "⭐ المفضلة")
        entries.putAll(Stations.categories)
        entries.forEach { (key, title) ->
            val chip = Chip(this).apply {
                text = title
                isCheckable = true
                isCheckedIconVisible = false
                tag = key
                isChecked = key == "all"
                setOnClickListener { currentCat = key; refresh() }
            }
            group.addView(chip)
        }
    }

    private fun refresh() {
        adapter.items = Stations.all.filter { s ->
            (currentCat == "all" || (currentCat == "fav" && s.url in favs) || s.cat == currentCat) &&
                (query.isEmpty() || s.name.contains(query))
        }
        adapter.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            val c = controller ?: return@addListener
            c.addListener(listener)
            updateBar(c)
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { controller?.let { updateBar(it) } }
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@MainActivity, "تعذّر تشغيل الإذاعة، تحقق من الاتصال أو جرّب لاحقاً", Toast.LENGTH_LONG).show()
        }
    }

    private fun play(s: Station) {
        val c = controller ?: run {
            Toast.makeText(this, "جارِ تجهيز المشغّل…", Toast.LENGTH_SHORT).show(); return
        }
        val item = MediaItem.Builder()
            .setMediaId(s.url)
            .setUri(s.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.name)
                    .setArtist(Stations.categories[s.cat] ?: "")
                    .setStation(s.name)
                    .build()
            ).build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        prefs.edit().putString("last", s.url).apply()
    }

    private fun updateBar(c: MediaController) {
        val item = c.currentMediaItem
        if (item == null) { player.visibility = View.GONE; return }
        player.visibility = View.VISIBLE
        nowTitle.text = item.mediaMetadata.title ?: ""
        nowStatus.text = when {
            c.playerError != null -> "⚠ خطأ في البث"
            c.playbackState == Player.STATE_BUFFERING -> "⏳ جارِ التحميل…"
            c.isPlaying -> "🔴 بث مباشر"
            else -> "⏸ متوقف"
        }
        btnPlay.setImageResource(
            if (c.playWhenReady && c.playerError == null) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        adapter.playingUrl = item.mediaId
        adapter.notifyDataSetChanged()
    }

    private fun toggleFav(s: Station) {
        if (!favs.remove(s.url)) favs.add(s.url)
        prefs.edit().putStringSet("favs", HashSet(favs)).apply()
        refresh()
    }

    inner class StationAdapter : BaseAdapter() {
        var items: List<Station> = emptyList()
        var playingUrl: String? = null
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
            val s = items[position]
            val playing = s.url == playingUrl
            v.findViewById<TextView>(R.id.icon).text = if (playing) "🔊" else Stations.icon(s.cat)
            v.findViewById<TextView>(R.id.name).apply {
                text = s.name
                setTextColor(ContextCompat.getColor(context, if (playing) R.color.accent else R.color.text))
            }
            v.findViewById<TextView>(R.id.cat).text = Stations.categories[s.cat] ?: ""
            v.findViewById<TextView>(R.id.fav).apply {
                text = if (s.url in favs) "★" else "☆"
                setOnClickListener { toggleFav(s) }
            }
            return v
        }
    }
}
