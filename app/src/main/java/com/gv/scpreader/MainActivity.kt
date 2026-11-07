package com.gv.scpreader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gv.scpreader.databinding.ActivityMainBinding
import com.gv.scpreader.databinding.SheetSettingsBinding
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLUG = "slug"
        private const val DEFAULT_SLUG = "scp-173"
        private val WIKI_HOSTS = setOf(
            "scp-wiki.wikidot.com", "scpwiki.com", "www.scpwiki.com", "scp-wiki.net", "www.scp-wiki.net",
        )

        /** Page slug for a wiki URL ("scp-173", "forum/t-123", ...), or null if it's not the wiki. */
        /**
         * The article a wiki path belongs to. Wikidot appends parameters to page paths
         * ("scp-7004/offset/1" when paging through an article's sections), which must not
         * break next/prev, bookmarks or read tracking. Other paths (forum threads) are kept whole.
         */
        fun articleSlug(path: String) = if (path.startsWith("scp-")) path.substringBefore('/') else path

        fun slugFromUrl(url: String?): String? {
            val u = Uri.parse(url ?: return null)
            if (u.host?.lowercase() !in WIKI_HOSTS) return null
            return u.path.orEmpty().trim('/').ifEmpty { "main" }
        }
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var slug: String? = null
    private var currentPath: String? = null
    private var pageTitle: String = ""
    private var pageReady = false
    private var markedRead = false
    private var pendingRestore: Float? = null
    @Volatile private var blockAds = true

    private var docStartHandler: ScriptHandler? = null
    private val docStartSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        saveScroll()
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(prefs.exportJson().toByteArray()) }
        }.onSuccess {
            Toast.makeText(this, "Progress exported", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            prefs.importJson(text)
        }.onSuccess { r ->
            applySettings()
            updateBookmarkIcon()
            if (pageReady) updateNav()
            MaterialAlertDialogBuilder(this)
                .setTitle("Progress imported")
                .setMessage("Added ${r.bookmarks} bookmarks, ${r.read} read articles and ${r.history} history entries. " +
                    "Reader settings were restored from the backup.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }.onFailure {
            Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Keeps the fullscreen clock and battery readout current. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) updateBattery(intent) else updateClock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        setupToolbar()
        setupWebView()
        setupBottomBar()
        applySettings()

        onBackPressedDispatcher.addCallback(this) {
            if (b.web.canGoBack()) b.web.goBack() else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        if (savedInstanceState == null || b.web.restoreState(savedInstanceState) == null) {
            val fromIntent = intentSlug(intent)
            val last = prefs.lastSlug
            when {
                fromIntent != null -> open(fromIntent)
                last != null -> {
                    pendingRestore = prefs.scrollFor(last)
                    open(last)
                }
                else -> {
                    open(DEFAULT_SLUG)
                    Snackbar.make(b.root, "Welcome. Tap ☰ for the full SCP list.", Snackbar.LENGTH_LONG)
                        .setAnchorView(b.bottomBar).show()
                }
            }
        }
        prefetchIndexes()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentSlug(intent)?.let { open(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.web.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // Battery changes are a sticky broadcast, so this also returns the current level right away.
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let(::updateBattery)
        updateClock()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and the keyboard can bring the system bars back; hide them again.
        if (hasFocus) applyFullscreen()
    }

    override fun onPause() {
        super.onPause()
        saveScroll()
    }

    override fun onResume() {
        super.onResume()
        // Read/bookmark state may have changed in the list screen.
        updateBookmarkIcon()
    }

    private fun intentSlug(i: Intent?): String? =
        i?.getStringExtra(EXTRA_SLUG) ?: slugFromUrl(i?.dataString)

    fun open(target: String) {
        b.web.loadUrl(ScpRepository.urlFor(target))
    }

    /** Download series indexes in the background so next/prev/random/titles work everywhere. */
    private fun prefetchIndexes() = lifecycleScope.launch {
        var fetched = false
        for (s in ScpRepository.SERIES) {
            if (ScpRepository.cached(this@MainActivity, s) != null) continue
            runCatching { ScpRepository.load(this@MainActivity, s) }.onSuccess { fetched = true }
        }
        if (fetched && pageReady) updateNav()
    }

    // ---------------------------------------------------------------- UI setup

    private fun setupToolbar() {
        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.menu.findItem(R.id.action_donate).isVisible = BuildConfig.SHOW_DONATE
        b.toolbar.setOnClickListener {
            b.web.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null)
        }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_goto -> showGoToDialog()
                R.id.action_settings -> showSettings()
                R.id.action_fullscreen -> {
                    prefs.fullscreen = !prefs.fullscreen
                    applySettings()
                }
                R.id.action_bookmarks -> openList("bookmarks")
                R.id.action_history -> openList("history")
                R.id.action_expand -> b.web.evaluateJavascript(ReaderStyle.expandAllJs(), null)
                R.id.action_home -> open("main")
                R.id.action_share -> share()
                R.id.action_browser -> openExternal(Uri.parse(b.web.url ?: ScpRepository.BASE))
                R.id.action_export -> exportLauncher.launch(
                    "scp-reader-progress-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json"
                )
                R.id.action_import -> importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                R.id.action_donate -> openExternal(Uri.parse(BuildConfig.DONATE_URL))
            }
            true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val w = b.web
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lay out at device width; the wiki's fixed-width desktop layout is overridden by our CSS.
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, false)
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (scheme == "javascript" || scheme == "about" || scheme == "data") return false
                if (scheme != "http" && scheme != "https") {
                    openExternal(url); return true
                }
                val host = url.host?.lowercase().orEmpty()
                if (host in WIKI_HOSTS) {
                    if (host != "scp-wiki.wikidot.com" || scheme == "http") {
                        view.loadUrl(url.buildUpon().scheme("https").authority("scp-wiki.wikidot.com").build().toString())
                        return true
                    }
                    return false
                }
                if (host.endsWith(".wdfiles.com") || host == "wdfiles.com") return false
                if (!request.isForMainFrame) return false
                openExternal(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (blockAds && ReaderStyle.isAdHost(request.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                saveScroll()
                pageReady = false
                markedRead = false
                b.readProgress.progress = 0
                slugFromUrl(url)?.let { path ->
                    val s = articleSlug(path)
                    currentPath = path
                    slug = s
                    val e = ScpRepository.find(this@MainActivity, s)
                    b.toolbar.title = e?.code ?: s
                    b.toolbar.subtitle = e?.title
                }
                if (!docStartSupported) view.evaluateJavascript(ReaderStyle.documentStartJs(ReaderStyle.css(prefs), tapZone()), null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                b.swipe.isRefreshing = false
                if (!docStartSupported) view.evaluateJavascript(ReaderStyle.documentStartJs(ReaderStyle.css(prefs), tapZone()), null)
                afterLoad()
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.loadProgress.isVisible = newProgress < 100
                b.loadProgress.setProgressCompat(newProgress, true)
            }
        }
        w.onScrollFraction = { f -> onScrolled(f) }

        b.swipe.setOnRefreshListener { b.web.reload() }
        // Only allow pull-to-refresh when actually at the top of the page.
        b.swipe.setOnChildScrollUpCallback { _, _ -> b.web.scrollY > 0 }
    }

    private fun setupBottomBar() {
        b.btnPrev.setOnClickListener { slug?.let { s -> ScpRepository.neighbor(this, s, -1)?.let(::open) } }
        b.btnNext.setOnClickListener { slug?.let { s -> ScpRepository.neighbor(this, s, +1)?.let(::open) } }
        b.btnList.setOnClickListener {
            openList(slug?.let { ScpRepository.seriesFor(it)?.id } ?: "1")
        }
        b.btnRandom.setOnClickListener { open(ScpRepository.randomSlug(this)) }
        b.btnBookmark.setOnClickListener {
            val s = slug ?: return@setOnClickListener
            val on = prefs.toggleBookmark(s, pageTitle.ifEmpty { s })
            updateBookmarkIcon()
            Toast.makeText(this, if (on) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
        }
        b.btnList.setOnLongClickListener { openList("bookmarks"); true }
    }

    // ---------------------------------------------------------------- page lifecycle

    private fun afterLoad() {
        val s = slug ?: return
        val next = if (prefs.nextCard) ScpRepository.neighbor(this, s, +1) else null
        val nextEntry = next?.let { ScpRepository.find(this, it) }
        val js = ReaderStyle.afterLoadJs(
            prefs.autoExpand, next, nextEntry?.code ?: next?.uppercase(), nextEntry?.title,
        )
        b.web.evaluateJavascript(js) { raw ->
            val meta = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
            onPageMeta(s, meta)
        }
    }

    private fun onPageMeta(s: String, meta: JSONObject?) {
        if (s != slug) return
        val entry = ScpRepository.find(this, s)
        val rawTitle = meta?.optString("title").orEmpty()
        val rating = meta?.optString("rating").orEmpty()
        val tagsArr = meta?.optJSONArray("tags")
        val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr!!.getString(it) }

        pageTitle = if (entry != null) "${entry.code} — ${entry.title}" else rawTitle.ifEmpty { s }
        b.toolbar.title = entry?.code ?: rawTitle.ifEmpty { s }

        val sub = SpannableStringBuilder()
        entry?.title?.let { sub.append(it) }
        ReaderStyle.objectClass(tags)?.let { (name, color) ->
            if (sub.isNotEmpty()) sub.append("  ·  ")
            sub.inSpans(ForegroundColorSpan(color)) { append(name) }
        }
        if (rating.isNotEmpty()) {
            if (sub.isNotEmpty()) sub.append("  ·  ")
            sub.append("★ ").append(rating)
        }
        b.toolbar.subtitle = sub.ifEmpty { null }

        // Reopen the exact address (including e.g. "/offset/1"), not just the article.
        prefs.lastSlug = currentPath ?: s
        prefs.addHistory(s, pageTitle)
        pageReady = true
        updateNav()
        updateBookmarkIcon()
        handleScrollRestore(s)
    }

    private fun handleScrollRestore(s: String) {
        val auto = pendingRestore
        pendingRestore = null
        if (auto != null) {
            if (auto > 0.02f) b.web.postDelayed({ scrollToFraction(auto) }, 250)
            return
        }
        val saved = prefs.scrollFor(s)
        if (saved !in 0.03f..0.95f) return
        b.web.postDelayed({
            if (s != slug || kotlin.math.abs(b.web.scrollFraction() - saved) < 0.05f) return@postDelayed
            Snackbar.make(b.root, "Resume where you left off (${(saved * 100).toInt()}%)?", 6000)
                .setAnchorView(b.bottomBar)
                .setAction("Resume") { scrollToFraction(saved) }
                .show()
        }, 400)
    }

    private fun scrollToFraction(f: Float) =
        b.web.evaluateJavascript(ReaderStyle.scrollToFractionJs(f), null)

    private fun onScrolled(f: Float) {
        b.readProgress.progress = (f * 1000).toInt()
        if (b.statusStrip.isVisible) b.readPct.text = "${(f * 100).toInt()}%"
        val s = slug ?: return
        if (pageReady && !markedRead && f > 0.9f && b.web.scrollY > 0) {
            markedRead = true
            prefs.setRead(s, true)
        }
    }

    private fun saveScroll() {
        val s = slug ?: return
        if (pageReady) prefs.saveScroll(s, b.web.scrollFraction())
    }

    private fun updateNav() {
        val s = slug
        fun label(target: String?) = target?.let {
            (ScpRepository.find(this, it)?.code ?: it.uppercase()).removePrefix("SCP-")
        }.orEmpty()
        val prev = s?.let { ScpRepository.neighbor(this, it, -1) }
        val next = s?.let { ScpRepository.neighbor(this, it, +1) }
        b.btnPrev.text = label(prev)
        b.btnNext.text = label(next)
        b.btnPrev.isEnabled = prev != null
        b.btnNext.isEnabled = next != null
        b.btnPrev.alpha = if (prev != null) 1f else 0.35f
        b.btnNext.alpha = if (next != null) 1f else 0.35f
    }

    private fun updateBookmarkIcon() {
        val on = slug?.let { prefs.isBookmarked(it) } == true
        b.btnBookmark.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border)
        val pal = ReaderStyle.palette(prefs)
        b.btnBookmark.imageTintList = ColorStateList.valueOf(
            if (on) getColor(R.color.star) else Color.parseColor(pal.text)
        )
    }

    // ---------------------------------------------------------------- settings & theming

    private fun applySettings() {
        // Size is applied as CSS zoom in ReaderStyle; keep WebView's font-only scaling neutral.
        b.web.settings.textZoom = 100
        blockAds = prefs.blockAds
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreen()
        applyChrome()

        val css = ReaderStyle.css(prefs)
        if (docStartSupported) {
            docStartHandler?.remove()
            docStartHandler = WebViewCompat.addDocumentStartJavaScript(b.web, ReaderStyle.documentStartJs(css, tapZone()), setOf("*"))
            b.web.evaluateJavascript(
                "window.__gvApply && window.__gvApply(${JSONObject.quote(css)});" +
                    "window.__gvRelax && setTimeout(window.__gvRelax, 50)", null,
            )
        } else {
            b.web.evaluateJavascript(ReaderStyle.documentStartJs(css, tapZone()), null)
        }
        b.web.evaluateJavascript("window.__gvTapZone = ${tapZone()}", null)
    }

    private fun tapZone() = if (prefs.tapPaging) prefs.tapZone else 0

    private val hideTapZones = Runnable {
        b.tapZoneLeft.isVisible = false
        b.tapZoneRight.isVisible = false
    }

    /** Briefly shades the page-turn tap areas so their size is visible while adjusting it. */
    private fun previewTapZones() {
        b.root.removeCallbacks(hideTapZones)
        if (!prefs.tapPaging) { hideTapZones.run(); return }
        val w = b.web.width * prefs.tapZone / 100
        val tint = Color.parseColor(ReaderStyle.palette(prefs).accent) and 0x00FFFFFF or 0x40000000
        listOf(b.tapZoneLeft, b.tapZoneRight).forEach {
            it.layoutParams = it.layoutParams.apply { width = w }
            it.setBackgroundColor(tint)
            it.isVisible = true
        }
        b.root.postDelayed(hideTapZones, 1500)
    }

    /** Paints the native toolbar/bottom bar in the active reader palette. */
    private fun applyChrome() {
        val p = ReaderStyle.palette(prefs)
        val bg = Color.parseColor(p.bg)
        val surface = Color.parseColor(p.surface)
        val text = Color.parseColor(p.text)
        val muted = Color.parseColor(p.muted)
        val accent = Color.parseColor(p.accent)
        val textTint = ColorStateList.valueOf(text)

        b.root.setBackgroundColor(bg)
        b.web.setBackgroundColor(bg)
        b.toolbar.setBackgroundColor(bg)
        b.toolbar.setTitleTextColor(text)
        b.toolbar.setSubtitleTextColor(muted)
        b.toolbar.overflowIcon?.setTint(text)
        for (i in 0 until b.toolbar.menu.size()) b.toolbar.menu.getItem(i).icon?.setTint(text)
        b.bottomBar.setBackgroundColor(surface)
        b.statusStrip.setBackgroundColor(surface)
        b.clock.setTextColor(text)
        b.readPct.setTextColor(muted)
        b.battery.setTextColor(text)
        lastBattery?.let(::updateBattery)
        listOf(b.btnList, b.btnRandom).forEach { it.imageTintList = textTint }
        listOf(b.btnPrev, b.btnNext).forEach {
            it.iconTint = textTint
            it.setTextColor(textTint)
        }
        b.readProgress.setIndicatorColor(accent)
        b.loadProgress.setIndicatorColor(accent)
        b.swipe.setProgressBackgroundColorSchemeColor(surface)
        b.swipe.setColorSchemeColors(accent)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = bg
            window.navigationBarColor = surface
        }
        updateBookmarkIcon()
    }

    private fun showSettings() {
        val sb = SheetSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sb.root)

        ReaderStyle.PALETTES.forEachIndexed { i, pal ->
            sb.themeChips.addView(Chip(this).apply {
                id = 1000 + i
                text = pal.name
                isCheckable = true
                isChecked = prefs.theme == i
                chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(pal.surfaceHi))
                setTextColor(Color.parseColor(pal.text))
                chipStrokeColor = ColorStateList.valueOf(Color.parseColor(pal.accent))
                chipStrokeWidth = if (isChecked) 4f else 0f
                setOnCheckedChangeListener { c, checked ->
                    (c as Chip).chipStrokeWidth = if (checked) 4f else 0f
                    if (checked) { prefs.theme = i; applySettings() }
                }
            })
        }
        ReaderStyle.FONTS.forEachIndexed { i, (name, _) ->
            sb.fontChips.addView(Chip(this).apply {
                id = 2000 + i
                text = name
                isCheckable = true
                isChecked = prefs.font == i
                setOnCheckedChangeListener { _, checked -> if (checked) { prefs.font = i; applySettings() } }
            })
        }

        fun sizeLabel() { sb.sizeLabel.text = "Text size  ${prefs.textZoom}%" }
        fun lineLabel() { sb.lineLabel.text = "Line spacing  %.1f".format(prefs.lineHeight) }
        sb.sizeSlider.value = prefs.textZoom.toFloat().coerceIn(70f, 200f)
        sb.lineSlider.value = (Math.round(prefs.lineHeight * 10) / 10f).coerceIn(1.2f, 2.2f)
        sizeLabel(); lineLabel()
        sb.sizeSlider.addOnChangeListener { _, v, _ ->
            prefs.textZoom = v.toInt(); sizeLabel(); applySettings()
        }
        sb.lineSlider.addOnChangeListener { _, v, _ -> prefs.lineHeight = v; lineLabel(); applySettings() }

        fun bind(sw: com.google.android.material.materialswitch.MaterialSwitch, get: Boolean, set: (Boolean) -> Unit) {
            sw.isChecked = get
            sw.setOnCheckedChangeListener { _, v -> set(v); applySettings() }
        }
        bind(sb.swFullscreen, prefs.fullscreen) { prefs.fullscreen = it }
        bind(sb.swStrict, prefs.strictDark) { prefs.strictDark = it }
        bind(sb.swDimImages, prefs.dimImages) { prefs.dimImages = it }
        bind(sb.swExpand, prefs.autoExpand) { prefs.autoExpand = it; if (it) b.web.evaluateJavascript(ReaderStyle.expandAllJs(), null) }
        bind(sb.swVolume, prefs.volumeKeys) { prefs.volumeKeys = it }
        fun zoneLabel() { sb.tapZoneLabel.text = "Page-turn area  ${prefs.tapZone}% each side" }
        sb.tapZoneSlider.value = prefs.tapZone.toFloat().coerceIn(10f, 45f)
        sb.tapZoneSlider.isEnabled = prefs.tapPaging
        zoneLabel()
        sb.tapZoneSlider.addOnChangeListener { _, v, _ ->
            prefs.tapZone = v.toInt(); zoneLabel(); applySettings(); previewTapZones()
        }
        bind(sb.swTapPaging, prefs.tapPaging) {
            prefs.tapPaging = it
            sb.tapZoneSlider.isEnabled = it
            b.root.post { previewTapZones() }
        }
        bind(sb.swAutoNext, prefs.nextCard) { prefs.nextCard = it; afterLoad() }
        bind(sb.swKeepOn, prefs.keepScreenOn) { prefs.keepScreenOn = it }
        bind(sb.swAds, prefs.blockAds) { prefs.blockAds = it }

        dialog.show()
    }

    /** Hides the system status/navigation bars and shows our own time + battery strip instead. */
    private fun applyFullscreen() {
        val on = prefs.fullscreen
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        b.statusStrip.isVisible = on
        b.toolbar.menu.findItem(R.id.action_fullscreen)?.isChecked = on
        if (on) {
            updateClock()
            b.readPct.text = if (pageReady) "${(b.web.scrollFraction() * 100).toInt()}%" else ""
        }
    }

    private fun updateClock() {
        b.clock.text = DateFormat.getTimeFormat(this).format(java.util.Date())
    }

    private var lastBattery: Intent? = null

    private fun updateBattery(i: Intent) {
        lastBattery = i
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pal = ReaderStyle.palette(prefs)
        val color = if (pct <= 15 && !charging) Color.parseColor(pal.accent) else Color.parseColor(pal.text)
        b.battery.text = "$pct%"
        b.battery.setTextColor(color)
        val icon = getDrawable(if (charging) R.drawable.ic_battery_charging else R.drawable.ic_battery)?.mutate()
        icon?.setTint(color)
        // Icon after the number: the text is right-aligned, and a start drawable would sit at the
        // view's far left, next to the reading percentage.
        b.battery.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null)
    }

    // ---------------------------------------------------------------- misc actions

    private fun showGoToDialog() {
        val input = EditText(this).apply {
            hint = "173, 049-J, or a title"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to)
            .setView(box)
            .setPositiveButton("Go") { _, _ -> goTo(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> goTo(input.text.toString()); dialog.dismiss(); true }
        dialog.setOnShowListener { input.requestFocus() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun goTo(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val s = ScpRepository.slugFromInput(t)
        if (s != null) open(s) else openList("all", t)
    }

    private fun openList(tab: String, query: String? = null) {
        startActivity(Intent(this, ListActivity::class.java)
            .putExtra(ListActivity.EXTRA_TAB, tab)
            .putExtra(ListActivity.EXTRA_QUERY, query))
    }

    private fun share() {
        val url = b.web.url ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, pageTitle)
            putExtra(Intent.EXTRA_TEXT, if (pageTitle.isNotEmpty()) "$pageTitle\n$url" else url)
        }, getString(R.string.share)))
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                b.web.evaluateJavascript(ReaderStyle.scrollByScreenJs(dir), null)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        docStartHandler?.remove()
        b.web.destroy()
        super.onDestroy()
    }
}
