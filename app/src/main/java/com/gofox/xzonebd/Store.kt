package com.gofox.xzonebd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gofox", Context.MODE_PRIVATE)

    var engine: String
        get() = sp.getString("engine", "google") ?: "google"
        set(v) { sp.edit().putString("engine", v).apply() }
    var homeUrl: String
        get() = sp.getString("home", "") ?: ""
        set(v) { sp.edit().putString("home", v).apply() }
    var desktop: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) { sp.edit().putBoolean("desktop", v).apply() }

    private fun load(k: String, def: String = "[]"): JSONArray =
        try { JSONArray(sp.getString(k, def)) } catch (e: Exception) { JSONArray() }
    private fun save(k: String, a: JSONArray) { sp.edit().putString(k, a.toString()).apply() }
    private fun keep(a: JSONArray, f: (JSONObject) -> Boolean): JSONArray {
        val r = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (f(o)) r.put(o) }
        return r
    }
    private fun prepend(a: JSONArray, o: JSONObject, max: Int): JSONArray {
        val r = JSONArray().put(o)
        for (i in 0 until minOf(a.length(), max - 1)) r.put(a.getJSONObject(i))
        return r
    }

    fun shortcuts(): JSONArray = load("shortcuts", DEFAULT_SHORTCUTS)
    fun addShortcut(n: String, u: String) {
        val a = keep(shortcuts()) { it.getString("u") != u }
        a.put(JSONObject().put("n", n).put("u", u))
        save("shortcuts", a)
    }
    fun removeShortcut(u: String) = save("shortcuts", keep(shortcuts()) { it.getString("u") != u })

    fun bookmarks(): JSONArray = load("bookmarks")
    fun addBookmark(t: String, u: String): Boolean {
        val a = bookmarks()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("u") == u) return false
        save("bookmarks", prepend(a, JSONObject().put("t", t).put("u", u), 1000))
        return true
    }
    fun removeBookmark(u: String) = save("bookmarks", keep(bookmarks()) { it.getString("u") != u })

    fun history(): JSONArray = load("history")
    fun addHistory(t: String, u: String) {
        val a = keep(history()) { it.getString("u") != u }
        val now = System.currentTimeMillis()
        val o = JSONObject().put("id", now.toString()).put("t", t).put("u", u).put("time", now)
        save("history", prepend(a, o, 500))
    }
    fun deleteHistory(id: String) = save("history", keep(history()) { it.getString("id") != id })
    fun clearHistory() = save("history", JSONArray())

    fun downloads(): JSONArray = load("downloads")
    fun addDownload(id: Long, name: String) =
        save("downloads", prepend(downloads(), JSONObject().put("id", id).put("name", name), 200))
    fun removeDownload(id: Long) = save("downloads", keep(downloads()) { it.getLong("id") != id })

    companion object {
        const val DEFAULT_SHORTCUTS = "[{\"n\":\"Google\",\"u\":\"https://www.google.com\"},{\"n\":\"YouTube\",\"u\":\"https://m.youtube.com\"},{\"n\":\"Facebook\",\"u\":\"https://m.facebook.com\"},{\"n\":\"Wikipedia\",\"u\":\"https://www.wikipedia.org\"}]"
    }
}
