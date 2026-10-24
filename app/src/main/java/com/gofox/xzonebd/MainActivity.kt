package com.gofox.xzonebd

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Tab(val web: WebView) {
    var title = ""
    var url = ""
    var isHome = false
}

class MainActivity : Activity() {

    companion object {
        const val ASSET = "file:///android_asset/"
        const val HOME = ASSET + "home.html"
        const val DESK_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val ABOUT = "https://gofox.fwh.is/about"
    }

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var lockView: TextView
    private lateinit var urlEdit: EditText
    private lateinit var tabCount: TextView
    private lateinit var panel: WebView
    private lateinit var toastView: TextView
    private lateinit var fullBox: FrameLayout

    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private var barF = 1f
    private var barTarget = 1f
    private var barAnim: ValueAnimator? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingApk = -1L
    @Volatile private var homeActive = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun statusH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun glass(r: Int, a: Int = 0xD9): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(a, 255, 255, 255))
        cornerRadius = dp(r).toFloat()
        setStroke(dp(1), Color.argb(180, 255, 255, 255))
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.statusBarColor = Color.parseColor("#FFF3EA")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildUi()
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        newTab(intent?.data?.toString())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { newTab(it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        if (pendingApk > 0 && packageManager.canRequestPackageInstalls()) {
            val id = pendingApk
            pendingApk = -1
            openDownload(id)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            panel.visibility == View.VISIBLE -> hidePanel()
            tabs[cur].web.canGoBack() -> tabs[cur].web.goBack()
            tabs.size > 1 -> closeTabAt(cur)
            else -> super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 11) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    // ------------------------------------------------------------ UI
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFFE1CC.toInt(), 0xFFE9D5FF.toInt(), 0xFFCFE0FF.toInt())
        )
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        // URL card
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(26)
            elevation = dp(6).toFloat()
            setPadding(dp(14), 0, dp(6), 0)
        }
        lockView = TextView(this).apply { text = "\uD83D\uDD12"; textSize = 14f }
        urlEdit = EditText(this).apply {
            background = null
            hint = "Search or type URL"
            setSingleLine()
            textSize = 15f
            setTextColor(0xFF1C1C1E.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(8), 0, dp(4), 0)
            setOnEditorActionListener { v, a, _ ->
                if (a == EditorInfo.IME_ACTION_GO) {
                    loadInCurrent(resolve(v.text.toString()))
                    v.clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else false
            }
            setOnFocusChangeListener { v, f ->
                val t = tabs.getOrNull(cur)
                if (t != null) (v as EditText).setText(if (f) t.url else shown(t.url))
            }
        }
        val refresh = iconBtn("\u21BB", 22f) { tabs[cur].web.reload() }
        tabCount = TextView(this).apply {
            text = "1"; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(0xFF333333.toInt())
            background = GradientDrawable().apply { setStroke(dp(2), 0xFF444444.toInt()); cornerRadius = dp(7).toFloat() }
            setOnClickListener { showPanel("tabs.html") }
        }
        val more = iconBtn("\u22EE", 24f) { showMenu() }
        bar.addView(lockView)
        bar.addView(urlEdit, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(refresh, LinearLayout.LayoutParams(dp(38), -1))
        bar.addView(tabCount, LinearLayout.LayoutParams(dp(24), dp(24)).apply { setMargins(dp(4), 0, dp(4), 0) })
        bar.addView(more, LinearLayout.LayoutParams(dp(38), -1))
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(12), dp(6), dp(12), 0) })

        // panel for Tabs / Bookmarks / History / Downloads / Settings
        panel = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFFF6EEFF.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(PanelBridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url.toString()
                    if (u.startsWith(ASSET)) return false
                    hidePanel(); loadInCurrent(u); return true
                }
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))

        fullBox = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(fullBox, FrameLayout.LayoutParams(-1, -1))

        toastView = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(0xFF1C1C1E.toInt())
            background = glass(22, 0xF2)
            elevation = dp(12).toFloat()
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        root.addView(toastView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })

        setContentView(root)
    }

    private fun iconBtn(t: String, size: Float, click: () -> Unit) = TextView(this).apply {
        text = t; textSize = size; gravity = Gravity.CENTER
        setTextColor(0xFF444444.toInt())
        setOnClickListener { click() }
    }

    private fun applyBar(f: Float) {
        barF = f
        bar.translationY = -(1 - f) * dp(64)
        (content.layoutParams as FrameLayout.LayoutParams).topMargin = (f * dp(64)).toInt()
        content.requestLayout()
    }

    private fun setBar(show: Boolean) {
        val t = if (show) 1f else 0f
        if (t == barTarget) return
        barTarget = t
        barAnim?.cancel()
        barAnim = ValueAnimator.ofFloat(barF, t).apply {
            duration = 180
            addUpdateListener { applyBar(it.animatedValue as Float) }
            start()
        }
    }

    private fun shown(u: String) = u.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private fun refreshUi() {
        val t = tabs.getOrNull(cur) ?: return
        homeActive = t.isHome
        tabCount.text = tabs.size.toString()
        barAnim?.cancel()
        if (t.isHome) {
            bar.visibility = View.GONE
            barTarget = 0f
            applyBar(0f)
        } else {
            bar.visibility = View.VISIBLE
            barTarget = 1f
            applyBar(1f)
            lockView.text = if (t.url.startsWith("https")) "\uD83D\uDD12" else "\uD83D\uDD13"
            if (!urlEdit.hasFocus()) urlEdit.setText(shown(t.url))
        }
    }

    // ------------------------------------------------------------ toast (always on top)
    private fun notify(m: String) = runOnUiThread {
        toastView.animate().cancel()
        toastView.text = m
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.translationY = -dp(24).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).withEndAction {
            toastView.animate().alpha(0f).translationY(-dp(24).toFloat()).setStartDelay(2200).setDuration(250)
                .withEndAction { toastView.visibility = View.GONE }.start()
        }.start()
    }

    // ------------------------------------------------------------ menu
    private fun showMenu() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(22, 0xF2)
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = PopupWindow(box, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(12).toFloat()
        fun item(label: String, act: () -> Unit) = box.addView(TextView(this).apply {
            text = label; textSize = 15f
            setTextColor(0xFF1C1C1E.toInt())
            setPadding(dp(22), dp(13), dp(22), dp(13))
            setOnClickListener { pw.dismiss(); act() }
        })
        item("New tab") { newTab() }
        item("Tabs (${tabs.size})") { showPanel("tabs.html") }
        item("Bookmarks") { showPanel("bookmarks.html") }
        item("History") { showPanel("history.html") }
        item("Downloads") { showPanel("downloads.html") }
        item("Add bookmark") { addBookmarkCurrent() }
        item(if (store.desktop) "Desktop site  \u2713" else "Desktop site") { toggleDesktop() }
        item("Settings") { showPanel("settings.html") }
        val off = statusH() + if (tabs[cur].isHome) dp(52) else dp(70)
        pw.showAtLocation(root, Gravity.TOP or Gravity.END, dp(14), off)
    }

    private fun addBookmarkCurrent() {
        val t = tabs[cur]
        if (t.isHome || !t.url.startsWith("http")) { notify("Open a website first"); return }
        notify(if (store.addBookmark(t.title.ifBlank { shown(t.url) }, t.url)) "Bookmark added" else "Already bookmarked")
    }

    private fun toggleDesktop() {
        store.desktop = !store.desktop
        tabs.forEach { applyUA(it.web); if (!it.isHome) it.web.reload() }
        notify(if (store.desktop) "Desktop site on" else "Desktop site off")
    }

    // ------------------------------------------------------------ panel
    private fun showPanel(page: String) {
        urlEdit.clearFocus()
        panel.loadUrl(ASSET + page)
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(160).start()
    }

    private fun hidePanel() {
        if (panel.visibility != View.VISIBLE) return
        panel.animate().cancel()
        panel.visibility = View.GONE
        panel.loadUrl("about:blank")
    }

    // ------------------------------------------------------------ tabs
    private fun start(): String = store.homeUrl.ifBlank { HOME }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String? = null) {
        val web = WebView(this)
        val tab = Tab(web)
        configure(web, tab)
        tabs.add(tab)
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        web.visibility = View.GONE
        val u = url ?: start()
        tab.url = u
        tab.isHome = u == HOME
        web.loadUrl(u)
        hidePanel()
        switchTo(tabs.size - 1)
    }

    private fun switchTo(i: Int) {
        cur = i
        tabs.forEachIndexed { j, t -> t.web.visibility = if (j == i) View.VISIBLE else View.GONE }
        refreshUi()
    }

    private fun closeTabAt(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) { newTab(); return }
        if (i < cur) cur-- else if (i == cur) cur = minOf(cur, tabs.size - 1)
        switchTo(cur)
    }

    private fun loadInCurrent(u: String) {
        val t = tabs[cur]
        t.url = u
        t.isHome = u == HOME
        t.web.loadUrl(u)
        refreshUi()
    }

    private fun applyUA(w: WebView) {
        val s = w.settings
        s.userAgentString = if (store.desktop) DESK_UA
        else WebSettings.getDefaultUserAgent(this).replace("; wv", "")
    }

    private fun engineUrl(q: String): String {
        val e = URLEncoder.encode(q, "UTF-8")
        return when (store.engine) {
            "bing" -> "https://www.bing.com/search?q=$e"
            "duckduckgo" -> "https://duckduckgo.com/?q=$e"
            "brave" -> "https://search.brave.com/search?q=$e"
            "yahoo" -> "https://search.yahoo.com/search?p=$e"
            else -> "https://www.google.com/search?q=$e"
        }
    }

    private fun resolve(s0: String): String {
        val s = s0.trim()
        if (s.isEmpty()) return start()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (!s.contains(" ") && (s.contains(".") || s.startsWith("localhost"))) return "https://$s"
        return engineUrl(s)
    }

    private fun normalize(u: String): String? {
        val s = u.trim()
        if (s.isEmpty() || s.contains(" ")) return null
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView, tab: Tab) {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false
        s.allowContentAccess = false
        applyUA(web)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(HomeBridge(), "Android")

        web.setOnScrollChangeListener { _, _, y, _, oy ->
            if (tabs.getOrNull(cur)?.web === web && !tab.isHome) {
                val d = y - oy
                if (y <= 0 || d < -6) setBar(true) else if (d > 6) setBar(false)
            }
        }
        web.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                tab.url = u
                tab.isHome = u == HOME
                if (tabs.getOrNull(cur) === tab) refreshUi()
            }

            override fun onPageFinished(v: WebView, u: String) {
                tab.title = v.title ?: ""
                if (!tab.isHome && u.startsWith("http")) store.addHistory(tab.title.ifBlank { shown(u) }, u)
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (u.startsWith("http") || u.startsWith(ASSET) || u.startsWith("about:") ||
                    u.startsWith("data:") || u.startsWith("blob:")
                ) return false
                try {
                    val i = if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, Uri.parse(u))
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i)
                } catch (e: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, t: String?) { tab.title = t ?: "" }

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = view
                customCb = cb
                fullBox.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullBox.visibility = View.VISIBLE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }

            override fun onHideCustomView() { exitFullscreen() }

            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(p.createIntent(), 11); true
                } catch (e: Exception) {
                    fileCb = null; false
                }
            }
        }
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullBox.removeView(v)
        fullBox.visibility = View.GONE
        customView = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        customCb?.onCustomViewHidden()
        customCb = null
    }

    // ------------------------------------------------------------ downloads
    private fun dm() = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) { notify("This download type is not supported"); return }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua ?: "")
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDirectory(Environment.DIRECTORY_DOWNLOADS, name)
            val id = dm().enqueue(req)
            store.addDownload(id, name)
            notify("Downloading $name")
        } catch (e: Exception) {
            notify("Download failed")
        }
    }

    private fun downloadsJson(): String {
        val out = JSONArray()
        val list = store.downloads()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            var st = "gone"; var tot = 0L; var sf = 0L
            dm().query(DownloadManager.Query().setFilterById(o.getLong("id"))).use { c ->
                if (c.moveToFirst()) {
                    st = when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> "done"
                        DownloadManager.STATUS_FAILED -> "failed"
                        DownloadManager.STATUS_PAUSED -> "paused"
                        else -> "running"
                    }
                    tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    sf = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_SO_FAR))
                }
            }
            out.put(JSONObject().put("id", o.getLong("id").toString()).put("name", o.getString("name"))
                .put("status", st).put("total", tot).put("sofar", sf))
        }
        return out.toString()
    }

    private fun nameOf(id: Long): String {
        val l = store.downloads()
        for (i in 0 until l.length()) if (l.getJSONObject(i).getLong("id") == id) return l.getJSONObject(i).getString("name")
        return ""
    }

    private fun openDownload(id: Long) {
        val uri = dm().getUriForDownloadedFile(id)
        if (uri == null) { notify("File not available"); return }
        val name = nameOf(id)
        var mime = dm().getMimeTypeForDownloadedFile(id)
        if (mime.isNullOrBlank() || mime == "application/octet-stream") {
            val ext = MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_"))
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
        }
        val isApk = mime == "application/vnd.android.package-archive" || name.endsWith(".apk", true)
        if (isApk) {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingApk = id
                notify("Allow GoFox to install apps, then come back")
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            mime = "application/vnd.android.package-archive"
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            notify("No app found to open this file")
        }
    }

    // ------------------------------------------------------------ bridges
    /** Small bridge for the Home page (loaded inside tabs). */
    inner class HomeBridge {
        @JavascriptInterface fun getShortcuts(): String = if (homeActive) store.shortcuts().toString() else "[]"

        @JavascriptInterface fun addShortcut(n: String, u: String) {
            if (!homeActive) return
            val url = normalize(u) ?: run { this@MainActivity.notify("Invalid link"); return }
            store.addShortcut(n.trim().ifBlank { Uri.parse(url).host ?: url }, url)
            this@MainActivity.notify("Shortcut added")
        }

        @JavascriptInterface fun removeShortcut(u: String) { if (homeActive) store.removeShortcut(u) }

        @JavascriptInterface fun openUrl(q: String) {
            if (homeActive) runOnUiThread { loadInCurrent(resolve(q)) }
        }

        @JavascriptInterface fun openMenu() {
            if (homeActive) runOnUiThread { showMenu() }
        }
    }

    /** Full bridge, only attached to the internal panel (never to websites). */
    inner class PanelBridge {
        @JavascriptInterface fun close() = runOnUiThread { hidePanel() }
        @JavascriptInterface fun notify(m: String) = this@MainActivity.notify(m)
        @JavascriptInterface fun openUrl(q: String) = runOnUiThread { hidePanel(); loadInCurrent(resolve(q)) }
        @JavascriptInterface fun openAbout() = runOnUiThread { hidePanel(); this@MainActivity.newTab(ABOUT) }

        @JavascriptInterface fun getBookmarks(): String = store.bookmarks().toString()
        @JavascriptInterface fun removeBookmark(u: String) = store.removeBookmark(u)

        @JavascriptInterface fun getHistory(): String = store.history().toString()
        @JavascriptInterface fun deleteHistory(id: String) = store.deleteHistory(id)
        @JavascriptInterface fun clearHistory() { store.clearHistory() }

        @JavascriptInterface fun getDownloads(): String = downloadsJson()
        @JavascriptInterface fun openDownload(id: String) = runOnUiThread { this@MainActivity.openDownload(id.toLong()) }
        @JavascriptInterface fun deleteDownload(id: String) {
            val l = id.toLong()
            try { dm().remove(l) } catch (e: Exception) { }
            store.removeDownload(l)
        }

        @JavascriptInterface fun getSettings(): String =
            JSONObject().put("engine", store.engine).put("home", store.homeUrl).toString()

        @JavascriptInterface fun setEngine(e: String) { store.engine = e }

        @JavascriptInterface fun setHomeUrl(u: String) {
            if (u.isBlank()) { store.homeUrl = ""; notify("Default home restored"); return }
            val n = normalize(u)
            if (n == null) notify("Invalid link") else { store.homeUrl = n; notify("Homepage saved") }
        }

        @JavascriptInterface fun clearData() {
            store.clearHistory()
            runOnUiThread {
                tabs.forEach { it.web.clearCache(true); it.web.clearHistory() }
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                notify("Browsing data cleared")
            }
        }

        @JavascriptInterface fun getTabs(): String {
            val a = JSONArray()
            tabs.forEachIndexed { i, t ->
                val title = if (t.isHome) "Home" else t.title.ifBlank { shown(t.url) }
                a.put(JSONObject().put("i", i).put("title", title).put("url", if (t.isHome) "" else shown(t.url)).put("cur", i == cur))
            }
            return a.toString()
        }

        @JavascriptInterface fun selectTab(i: Int) = runOnUiThread { hidePanel(); switchTo(i) }
        @JavascriptInterface fun closeTab(i: Int) = runOnUiThread { closeTabAt(i) }
        @JavascriptInterface fun newTab() = runOnUiThread { this@MainActivity.newTab(null) }
    }
}
