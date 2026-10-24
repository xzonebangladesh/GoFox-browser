package com.gofox.xzonebd

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

class Tab(val web: WebView) {
    var title = ""
    var url = ""
    var isHome = false
    var thumbnailBase64: String = ""
    var themeColor: Int? = null // ওয়েবসাইটের নিজস্ব মেটা থিম কালার
}

class MainActivity : Activity() {

    companion object {
        const val ASSET = "file:///android_asset/"
        const val HOME = ASSET + "home.html"
        const val DESK_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val ABOUT = "https://gofox.fwh.is/about"
    }

    lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var barContainer: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var lockView: ImageView
    private lateinit var urlEdit: EditText
    private lateinit var tabCount: TextView
    private lateinit var refreshBtn: ImageView
    private lateinit var moreBtn: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var panel: WebView
    private lateinit var toastView: TextView
    private lateinit var fullBox: FrameLayout

    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private var barF = 1f
    private var barTarget = 1f
    private var barAnim: ValueAnimator? = null
    private var progressAnim: ObjectAnimator? = null

    private var accumulatedScrollY = 0
    private val SCROLL_THRESHOLD = 50

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingApk = -1L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun statusH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
        applyAppTheme()
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)

        // পূর্বে খোলা থাকা ট্যাবগুলো রিস্টোর করা
        restoreSavedTabs()
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

    override fun onPause() {
        super.onPause()
        persistTabs()
    }

    override fun onStop() {
        super.onStop()
        persistTabs()
    }

    // ব্যাক প্রেস হ্যান্ডলিং: ওয়েবসাইট থেকে ব্যাক চাপলে হোমে আসবে
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val t = tabs.getOrNull(cur)
        when {
            customView != null -> exitFullscreen()
            panel.visibility == View.VISIBLE -> hidePanel()
            t != null && t.web.canGoBack() -> t.web.goBack()
            t != null && !t.isHome -> loadInCurrent(HOME)
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

    // ভেক্টর আইকন মেকার (জিরো ইমোজি)
    private fun createLockIcon(isSecure: Boolean): Drawable {
        val size = dp(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = if (isSecure) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        }
        if (isSecure) {
            val rect = RectF(dp(2).toFloat(), dp(6).toFloat(), (size - dp(2)).toFloat(), (size - dp(2)).toFloat())
            canvas.drawRoundRect(rect, dp(3).toFloat(), dp(3).toFloat(), paint)
            val shackle = RectF(dp(4.5f.toInt()).toFloat(), dp(2).toFloat(), (size - dp(4.5f.toInt())).toFloat(), dp(11).toFloat())
            canvas.drawArc(shackle, 180f, 180f, false, paint)
        } else {
            val path = Path().apply {
                moveTo(size / 2f, dp(2).toFloat())
                lineTo((size - dp(2)).toFloat(), (size - dp(2)).toFloat())
                lineTo(dp(2).toFloat(), (size - dp(2)).toFloat())
                close()
            }
            canvas.drawPath(path, paint)
        }
        return BitmapDrawable(resources, bmp)
    }

    private fun createRefreshIcon(color: Int): Drawable {
        val size = dp(18)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            this.color = color
        }
        val rect = RectF(dp(2).toFloat(), dp(2).toFloat(), (size - dp(2)).toFloat(), (size - dp(2)).toFloat())
        canvas.drawArc(rect, 45f, 280f, false, paint)
        paint.style = Paint.Style.FILL
        val arrow = Path().apply {
            moveTo((size - dp(2)).toFloat(), dp(3).toFloat())
            lineTo((size - dp(6)).toFloat(), dp(3).toFloat())
            lineTo((size - dp(2)).toFloat(), dp(7).toFloat())
            close()
        }
        canvas.drawPath(arrow, paint)
        return BitmapDrawable(resources, bmp)
    }

    private fun createMoreIcon(color: Int): Drawable {
        val size = dp(18)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        val r = dp(2).toFloat()
        canvas.drawCircle(size / 2f, dp(3.5f.toInt()).toFloat(), r, paint)
        canvas.drawCircle(size / 2f, size / 2f, r, paint)
        canvas.drawCircle(size / 2f, size - dp(3.5f.toInt()).toFloat(), r, paint)
        return BitmapDrawable(resources, bmp)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        root = FrameLayout(this)
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        barContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(3).toFloat()
            setPadding(dp(14), 0, dp(6), 0)
        }

        lockView = ImageView(this).apply {
            setImageDrawable(createLockIcon(true))
        }

        urlEdit = EditText(this).apply {
            background = null
            hint = "Search or type URL"
            setSingleLine()
            textSize = 15f
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

        refreshBtn = ImageView(this).apply {
            setImageDrawable(createRefreshIcon(Color.parseColor("#4B5563")))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { tabs[cur].web.reload() }
        }

        tabCount = TextView(this).apply {
            text = "1"
            textSize = 11f
            gravity = Gravity.CENTER
            setOnClickListener { showPanel("tabs.html") }
        }

        moreBtn = ImageView(this).apply {
            setImageDrawable(createMoreIcon(Color.parseColor("#4B5563")))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showMenu() }
        }

        bar.addView(lockView, LinearLayout.LayoutParams(dp(18), dp(18)))
        bar.addView(urlEdit, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(refreshBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        bar.addView(tabCount, LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(dp(6), 0, dp(6), 0) })
        bar.addView(moreBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
            val d = GradientDrawable().apply {
                setColor(Color.parseColor("#1A73E8"))
                cornerRadius = dp(2).toFloat()
            }
            progressDrawable = d
        }

        barContainer.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
        barContainer.addView(progressBar, LinearLayout.LayoutParams(-1, dp(2)).apply { setMargins(dp(14), dp(2), dp(14), 0) })

        root.addView(barContainer, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(6), dp(10), 0) })

        panel = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(PanelBridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url.toString()
                    if (u.startsWith(ASSET)) return false
                    hidePanel()
                    loadInCurrent(u)
                    return true
                }
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))

        fullBox = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(fullBox, FrameLayout.LayoutParams(-1, -1))

        toastView = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(Color.WHITE)
            elevation = dp(10).toFloat()
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        root.addView(toastView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })

        setContentView(root)
    }

    // কালার লাইট না ডার্ক তা নির্ধারণ করার অ্যালগরিদম
    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return darkness >= 0.45
    }

    // সিএসএস কালার পার্সার (Hex / RGB)
    private fun parseCssColor(str: String): Int? {
        var s = str.trim().lowercase()
        if (s.startsWith("#")) {
            if (s.length == 4) {
                s = "#" + s[1] + s[1] + s[2] + s[2] + s[3] + s[3]
            }
            return try { Color.parseColor(s) } catch (e: Exception) { null }
        }
        if (s.startsWith("rgb")) {
            val nums = s.substringAfter("(").substringBefore(")").split(",").map { it.trim() }
            if (nums.size >= 3) {
                val r = nums[0].toIntOrNull() ?: return null
                val g = nums[1].toIntOrNull() ?: return null
                val b = nums[2].toIntOrNull() ?: return null
                return Color.rgb(r, g, b)
            }
        }
        return try { Color.parseColor(s) } catch (e: Exception) { null }
    }

    // ক্রোমের মতো ডায়নামিক মেটা থিম কালার প্রয়োগ
    fun applyDynamicTheme(color: Int) {
        val isDark = isColorDark(color)
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#1F2937")
        val subTextColor = if (isDark) Color.parseColor("#D1D5DB") else Color.parseColor("#6B7280")

        window.statusBarColor = color
        window.navigationBarColor = color

        var flags = window.decorView.systemUiVisibility
        flags = if (isDark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            flags = if (isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags

        bar.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(24).toFloat()
            val strokeColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")
            setStroke(dp(1), strokeColor)
        }

        urlEdit.setTextColor(textColor)
        urlEdit.setHintTextColor(subTextColor)
        lockView.setImageDrawable(createLockIcon(tabs.getOrNull(cur)?.url?.startsWith("https") == true))
        refreshBtn.setImageDrawable(createRefreshIcon(textColor))
        moreBtn.setImageDrawable(createMoreIcon(textColor))

        tabCount.setTextColor(textColor)
        tabCount.background = GradientDrawable().apply {
            setStroke(dp(2), textColor)
            cornerRadius = dp(6).toFloat()
        }
    }

    // ডিফল্ট অ্যাপ থিম (ডার্ক / লাইট)
    fun applyAppTheme() {
        val dark = store.darkMode
        val bgColor = if (dark) Color.parseColor("#121212") else Color.parseColor("#F8F9FA")
        val surfaceColor = if (dark) Color.parseColor("#1E1E1E") else Color.WHITE
        val textColor = if (dark) Color.parseColor("#F3F4F6") else Color.parseColor("#1F2937")
        val subTextColor = if (dark) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
        val borderColor = if (dark) Color.parseColor("#2D2D2D") else Color.parseColor("#E5E7EB")

        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        var flags = window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags

        root.setBackgroundColor(bgColor)
        panel.setBackgroundColor(bgColor)

        bar.background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), borderColor)
        }

        urlEdit.setTextColor(textColor)
        urlEdit.setHintTextColor(subTextColor)

        tabCount.setTextColor(textColor)
        tabCount.background = GradientDrawable().apply {
            setStroke(dp(2), textColor)
            cornerRadius = dp(6).toFloat()
        }

        refreshBtn.setImageDrawable(createRefreshIcon(textColor))
        moreBtn.setImageDrawable(createMoreIcon(textColor))

        toastView.background = GradientDrawable().apply {
            setColor(if (dark) Color.parseColor("#2D2D2D") else Color.parseColor("#212529"))
            cornerRadius = dp(20).toFloat()
        }

        tabs.forEach { tab ->
            tab.web.evaluateJavascript("if(document.body){document.body.classList.toggle('dark', $dark);}", null)
        }
        panel.evaluateJavascript("if(document.body){document.body.classList.toggle('dark', $dark);}", null)
    }

    private fun animateProgress(newProgress: Int) {
        val target = newProgress * 10
        progressAnim?.cancel()
        if (progressBar.visibility != View.VISIBLE) {
            progressBar.alpha = 1f
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
        }
        progressAnim = ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, target).apply {
            duration = if (newProgress >= 100) 180 else 280
            interpolator = DecelerateInterpolator()
            start()
        }

        if (newProgress >= 100) {
            progressBar.animate().alpha(0f).setDuration(250).setStartDelay(150)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        progressBar.visibility = View.GONE
                        progressBar.progress = 0
                        progressBar.alpha = 1f
                    }
                }).start()
        }
    }

    private fun applyBar(f: Float) {
        barF = f
        barContainer.translationY = -(1 - f) * dp(62)
        (content.layoutParams as FrameLayout.LayoutParams).topMargin = (f * dp(58)).toInt()
        content.requestLayout()
    }

    private fun setBar(show: Boolean) {
        val t = if (show) 1f else 0f
        if (t == barTarget) return
        barTarget = t
        barAnim?.cancel()
        barAnim = ValueAnimator.ofFloat(barF, t).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyBar(it.animatedValue as Float) }
            start()
        }
    }

    private fun shown(u: String) = u.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private fun refreshUi() {
        val t = tabs.getOrNull(cur) ?: return
        tabCount.text = tabs.size.toString()
        barAnim?.cancel()
        if (t.isHome) {
            barContainer.visibility = View.GONE
            barTarget = 0f
            applyBar(0f)
            applyAppTheme()
        } else {
            barContainer.visibility = View.VISIBLE
            barTarget = 1f
            applyBar(1f)
            lockView.setImageDrawable(createLockIcon(t.url.startsWith("https")))
            if (!urlEdit.hasFocus()) urlEdit.setText(shown(t.url))
            // যদি সাইটের নিজস্ব মেটা থিম কালার থাকে তবে তা ব্যবহার হবে
            if (t.themeColor != null) {
                applyDynamicTheme(t.themeColor!!)
            } else {
                applyAppTheme()
            }
        }
    }

    private fun captureCurrentThumbnail() {
        val t = tabs.getOrNull(cur) ?: return
        if (t.isHome || t.web.width <= 0 || t.web.height <= 0) return
        try {
            val scale = 0.35f
            val w = (t.web.width * scale).toInt()
            val h = (t.web.height * scale).toInt()
            if (w <= 0 || h <= 0) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            t.web.draw(c)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            t.thumbnailBase64 = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            bmp.recycle()
        } catch (e: Exception) { }
    }

    private fun notify(m: String) = runOnUiThread {
        toastView.animate().cancel()
        toastView.text = m
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.translationY = -dp(20).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setDuration(180).withEndAction {
            toastView.animate().alpha(0f).translationY(-dp(20).toFloat()).setStartDelay(2000).setDuration(220)
                .withEndAction { toastView.visibility = View.GONE }.start()
        }.start()
    }

    private fun showMenu() {
        val isDark = store.darkMode
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (isDark) Color.parseColor("#2D2D2D") else Color.parseColor("#E5E7EB"))
            }
            elevation = dp(10).toFloat()
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = PopupWindow(box, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(12).toFloat()

        fun item(label: String, act: () -> Unit) = box.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1F2937"))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { pw.dismiss(); act() }
        })

        item("New tab") { newTab() }
        item("Tabs (${tabs.size})") { showPanel("tabs.html") }
        item("History") { showPanel("history.html") }
        item("Downloads") { showPanel("downloads.html") }
        item(if (store.desktop) "Desktop site  ✓" else "Desktop site") { toggleDesktop() }
        item("Settings") { showPanel("settings.html") }

        val off = statusH() + if (tabs[cur].isHome) dp(50) else dp(64)
        pw.showAtLocation(root, Gravity.TOP or Gravity.END, dp(14), off)
    }

    private fun toggleDesktop() {
        store.desktop = !store.desktop
        tabs.forEach { applyUA(it.web); if (!it.isHome) it.web.reload() }
        notify(if (store.desktop) "Desktop site on" else "Desktop site off")
    }

    fun showPanel(page: String) {
        captureCurrentThumbnail()
        urlEdit.clearFocus()
        barContainer.visibility = View.GONE
        panel.loadUrl(ASSET + page)
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(160).start()
    }

    fun hidePanel() {
        if (panel.visibility != View.VISIBLE) return
        panel.animate().cancel()
        panel.visibility = View.GONE
        panel.loadUrl("about:blank")
        refreshUi()
    }

    private fun start(): String = store.homeUrl.ifBlank { HOME }

    // সব খোলা ট্যাব লোকাল মেমোরিতে সংরক্ষণ
    private fun persistTabs() {
        try {
            val arr = JSONArray()
            tabs.forEach { t ->
                arr.put(JSONObject().apply {
                    put("url", t.url)
                    put("title", t.title)
                    put("isHome", t.isHome)
                })
            }
            store.saveTabs(arr.toString(), cur)
        } catch (e: Exception) { }
    }

    // অ্যাপ চালু হলে আগের সব ট্যাব পুনরুদ্ধার (Session Restore)
    private fun restoreSavedTabs() {
        val saved = store.getSavedTabs()
        val arr = try { JSONArray(saved) } catch (e: Exception) { JSONArray() }
        val intentUrl = intent?.data?.toString()

        if (arr.length() > 0) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("url", HOME)
                val isHome = o.optBoolean("isHome", u == HOME)
                val title = o.optString("title", "")
                createTabInstance(u, title, isHome)
            }
            val targetCur = store.getSavedCurTab().coerceIn(0, tabs.size - 1)
            switchTo(targetCur)
            if (!intentUrl.isNullOrBlank()) {
                newTab(intentUrl)
            }
        } else {
            newTab(intentUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTabInstance(url: String, savedTitle: String, isHome: Boolean) {
        val web = WebView(this)
        val tab = Tab(web).apply {
            this.url = url
            this.title = savedTitle
            this.isHome = isHome
        }
        configure(web, tab)
        tabs.add(tab)
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        web.visibility = View.GONE
        web.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(url: String? = null) {
        captureCurrentThumbnail()
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
        persistTabs()
    }

    private fun switchTo(i: Int) {
        cur = i
        tabs.forEachIndexed { j, t -> t.web.visibility = if (j == i) View.VISIBLE else View.GONE }
        accumulatedScrollY = 0
        refreshUi()
        persistTabs()
    }

    private fun closeTabAt(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) { 
            newTab()
            return 
        }
        if (i < cur) cur-- else if (i == cur) cur = minOf(cur, tabs.size - 1)
        switchTo(cur)
        persistTabs()
    }

    fun loadInCurrent(u: String) {
        val t = tabs[cur]
        t.url = u
        t.isHome = u == HOME
        t.thumbnailBase64 = ""
        t.themeColor = null
        t.web.loadUrl(u)
        refreshUi()
        persistTabs()
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
                val dy = y - oy
                if (y <= 15) {
                    accumulatedScrollY = 0
                    setBar(true)
                    return@setOnScrollChangeListener
                }
                if ((dy > 0 && accumulatedScrollY < 0) || (dy < 0 && accumulatedScrollY > 0)) {
                    accumulatedScrollY = 0
                }
                accumulatedScrollY += dy
                if (accumulatedScrollY > SCROLL_THRESHOLD) {
                    setBar(false)
                } else if (accumulatedScrollY < -SCROLL_THRESHOLD) {
                    setBar(true)
                }
            }
        }

        web.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                tab.url = u
                tab.isHome = u == HOME
                if (tabs.getOrNull(cur) === tab) {
                    refreshUi()
                    if (!tab.isHome) animateProgress(15)
                }
            }

            override fun onPageFinished(v: WebView, u: String) {
                tab.title = v.title ?: ""
                if (!tab.isHome && u.startsWith("http")) store.addHistory(tab.title.ifBlank { shown(u) }, u)
                if (tabs.getOrNull(cur) === tab) {
                    animateProgress(100)
                    v.postDelayed({ captureCurrentThumbnail() }, 600)
                }

                // ওয়েবসাইটের <meta name="theme-color"> এক্সট্র্যাক্ট করা
                if (!tab.isHome) {
                    val js = "(function(){ var m = document.querySelector('meta[name=\"theme-color\"]'); return m ? m.content : ''; })();"
                    v.evaluateJavascript(js) { res ->
                        val clean = res?.replace("\"", "")?.trim() ?: ""
                        val parsed = parseCssColor(clean)
                        tab.themeColor = parsed
                        if (tabs.getOrNull(cur) === tab) {
                            if (parsed != null) {
                                applyDynamicTheme(parsed)
                            } else {
                                applyAppTheme()
                            }
                        }
                    }
                }

                if (store.darkMode) {
                    v.evaluateJavascript("if(document.body){document.body.classList.add('dark');}", null)
                }
                persistTabs()
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
                } catch (e: Exception) { }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                if (tabs.getOrNull(cur)?.web === v && !tab.isHome) {
                    animateProgress(newProgress)
                }
            }

            override fun onReceivedTitle(v: WebView, t: String?) { 
                tab.title = t ?: ""
                persistTabs()
            }

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
        applyAppTheme()
        customCb?.onCustomViewHidden()
        customCb = null
    }

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
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
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
                    sf = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
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

    // --- HOME BRIDGE ---
    inner class HomeBridge {
        @JavascriptInterface fun getShortcuts(): String = store.shortcuts().toString()
        @JavascriptInterface fun getTabCount(): Int = tabs.size

        @JavascriptInterface fun addShortcut(n: String, u: String) {
            val url = normalize(u) ?: run { this@MainActivity.notify("Invalid link"); return }
            store.addShortcut(n.trim().ifBlank { Uri.parse(url).host ?: url }, url)
            this@MainActivity.notify("Shortcut added")
        }

        @JavascriptInterface fun removeShortcut(u: String) { 
            store.removeShortcut(u) 
        }

        @JavascriptInterface fun openUrl(q: String) {
            runOnUiThread { loadInCurrent(resolve(q)) }
        }

        @JavascriptInterface fun openMenu() {
            runOnUiThread { showMenu() }
        }

        @JavascriptInterface fun openTabs() {
            runOnUiThread { showPanel("tabs.html") }
        }

        @JavascriptInterface fun newTab() {
            runOnUiThread { this@MainActivity.newTab(null) }
        }

        @JavascriptInterface fun openPanel(page: String) {
            runOnUiThread { showPanel(page) }
        }

        @JavascriptInterface fun openAbout() {
            runOnUiThread { this@MainActivity.newTab(ABOUT) }
        }

        @JavascriptInterface fun isDarkMode(): Boolean = store.darkMode

        @JavascriptInterface fun toggleDarkMode(): Boolean {
            store.darkMode = !store.darkMode
            runOnUiThread { applyAppTheme() }
            return store.darkMode
        }
    }

    // --- PANEL BRIDGE ---
    inner class PanelBridge {
        @JavascriptInterface fun close() = runOnUiThread { hidePanel() }
        @JavascriptInterface fun notify(m: String) = this@MainActivity.notify(m)
        @JavascriptInterface fun openUrl(q: String) = runOnUiThread { hidePanel(); loadInCurrent(resolve(q)) }
        @JavascriptInterface fun openAbout() = runOnUiThread { hidePanel(); this@MainActivity.newTab(ABOUT) }

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
                a.put(
                    JSONObject()
                        .put("i", i)
                        .put("title", title)
                        .put("url", if (t.isHome) "" else shown(t.url))
                        .put("thumb", t.thumbnailBase64)
                        .put("cur", i == cur)
                )
            }
            return a.toString()
        }

        @JavascriptInterface fun selectTab(i: Int) = runOnUiThread { hidePanel(); switchTo(i) }
        @JavascriptInterface fun closeTab(i: Int) = runOnUiThread { closeTabAt(i) }
        @JavascriptInterface fun newTab() = runOnUiThread { this@MainActivity.newTab(null) }
        @JavascriptInterface fun isDarkMode(): Boolean = store.darkMode
    }
}
