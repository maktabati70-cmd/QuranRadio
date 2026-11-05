package com.quranradio.app

import android.content.Context
import org.json.JSONObject

data class Station(val name: String, val url: String, val cat: String)

object Stations {
    val categories = linkedMapOf<String, String>()
    val all = mutableListOf<Station>()

    fun load(ctx: Context) {
        if (all.isNotEmpty()) return
        val json = JSONObject(ctx.assets.open("radios.json").bufferedReader(Charsets.UTF_8).readText())
        val cats = json.getJSONObject("categories")
        cats.keys().forEach { categories[it] = cats.getString(it) }
        val arr = json.getJSONArray("stations")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            all += Station(o.getString("name"), o.getString("url"), o.getString("cat"))
        }
    }

    fun icon(cat: String) = when (cat) {
        "national" -> "📡"; "mixed" -> "📖"; "tafsir" -> "📚"; "hadith" -> "📜"
        "seerah" -> "🕌"; "ilm" -> "🎓"; "athkar" -> "🤲"; else -> "🎙"
    }
}
