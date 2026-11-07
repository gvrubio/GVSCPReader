package com.gv.scpreader

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.gv.scpreader.databinding.ActivityListBinding
import com.gv.scpreader.databinding.ItemEntryBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Browses the series indexes, plus bookmarks and history, with search and read tracking. */
class ListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_QUERY = "query"
        private const val TAB_BOOKMARKS = "bookmarks"
        private const val TAB_HISTORY = "history"
        private const val TAB_ALL = "all"
    }

    private data class Row(
        val slug: String,
        val code: String,
        val title: String,
        val sub: String? = null,
        val isJump: Boolean = false,
    )

    private lateinit var b: ActivityListBinding
    private lateinit var prefs: Prefs
    private val adapter = Adapter()
    private var tab = "1"
    private var query = ""
    private var source: List<Row> = emptyList()
    private var loadJob: Job? = null

    private val tabs: List<Pair<String, String>> by lazy {
        listOf(TAB_BOOKMARKS to "★ Bookmarks", TAB_HISTORY to "History", TAB_ALL to "All") +
            ScpRepository.SERIES.map { it.id to it.label }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        tab = intent.getStringExtra(EXTRA_TAB) ?: "1"
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()

        b.toolbar.title = getString(R.string.list)
        b.toolbar.setNavigationOnClickListener { finish() }
        setupMenu()

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.swipe.setColorSchemeColors(getColor(R.color.accent_soft))
        b.swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.surface))
        b.swipe.setOnRefreshListener { load(force = true) }

        tabs.forEachIndexed { i, (id, label) ->
            b.chips.addView(Chip(this).apply {
                this.id = 3000 + i
                text = label
                isCheckable = true
                isChecked = id == tab
                setOnCheckedChangeListener { _, checked ->
                    if (checked && tab != id) { tab = id; load() }
                }
            })
        }
        b.chips.post {
            val i = tabs.indexOfFirst { it.first == tab }
            b.chips.getChildAt(i)?.let { b.chipScroll.smoothScrollTo(it.left - 48, 0) }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshMarks() // read/bookmark marks may have changed
    }

    private fun setupMenu() {
        b.toolbar.inflateMenu(R.menu.list)
        val menu = b.toolbar.menu
        menu.findItem(R.id.action_hide_read).isChecked = prefs.hideRead
        val searchItem = menu.findItem(R.id.action_search)
        val sv = searchItem.actionView as SearchView
        sv.queryHint = getString(R.string.search_hint)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false.also { sv.clearFocus() }
            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty(); refilter(); return true
            }
        })
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            sv.setQuery(query, false)
            sv.clearFocus()
        }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_hide_read -> {
                    item.isChecked = !item.isChecked
                    prefs.hideRead = item.isChecked
                    refilter()
                }
                R.id.action_refresh -> load(force = true)
                R.id.action_clear -> { prefs.clearHistory(); if (tab == TAB_HISTORY) load() }
            }
            true
        }
    }

    private fun load(force: Boolean = false) {
        loadJob?.cancel()
        b.toolbar.menu.findItem(R.id.action_clear)?.isVisible = tab == TAB_HISTORY
        b.empty.isVisible = false
        when (tab) {
            TAB_BOOKMARKS -> showRows(prefs.bookmarks().map { refRow(it) }, "Long-press the ☰ button to jump here")
            TAB_HISTORY -> showRows(prefs.history().map { refRow(it) }, "Nothing read yet")
            else -> {
                val series = if (tab == TAB_ALL) ScpRepository.SERIES
                else listOfNotNull(ScpRepository.SERIES.firstOrNull { it.id == tab })
                b.swipe.isRefreshing = true
                loadJob = lifecycleScope.launch {
                    try {
                        val lists = series.map { s -> async { ScpRepository.load(this@ListActivity, s, force) } }.awaitAll()
                        val rows = lists.flatten().filter { it.exists }.map { Row(it.slug, it.code, it.title) }
                        showRows(rows, "No entries")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        showRows(emptyList(), "Couldn't load the list.\n${e.message ?: e.javaClass.simpleName}\nPull down to retry.")
                    } finally {
                        b.swipe.isRefreshing = false
                    }
                }
                return
            }
        }
        b.swipe.isRefreshing = false
    }

    private fun refRow(r: PageRef): Row {
        val e = ScpRepository.find(this, MainActivity.articleSlug(r.slug))
        val when_ = DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return Row(r.slug, e?.code ?: r.slug.uppercase(), e?.title ?: r.title.substringAfter(" — "), when_.toString())
    }

    private var emptyMessage = ""

    private fun showRows(rows: List<Row>, emptyMsg: String) {
        source = rows
        emptyMessage = emptyMsg
        refilter()
    }

    private fun refilter() {
        val q = query.trim().lowercase()
        val read = prefs.readSet()
        var rows = source
        if (prefs.hideRead && tab != TAB_HISTORY) rows = rows.filter { it.slug !in read }
        if (q.isNotEmpty()) {
            rows = rows.filter { it.code.lowercase().contains(q) || it.title.lowercase().contains(q) }
            ScpRepository.slugFromInput(q)?.let { jump ->
                if (rows.none { it.slug == jump }) {
                    val e = ScpRepository.find(this, jump)
                    rows = listOf(Row(jump, e?.code ?: jump.uppercase(), e?.title ?: "Open this page", isJump = true)) + rows
                }
            }
        }
        adapter.submit(rows)
        b.empty.text = emptyMessage
        b.empty.isVisible = rows.isEmpty() && !b.swipe.isRefreshing

        // Completion stats: a little motivation to work through a series.
        if (tab == TAB_BOOKMARKS || tab == TAB_HISTORY) {
            b.stats.text = "${source.size} pages"
        } else {
            val done = source.count { it.slug in read }
            val pct = if (source.isEmpty()) 0.0 else done * 100.0 / source.size
            b.stats.text = "${source.size} articles  ·  $done read (%.1f%%)".format(pct)
        }
    }

    private fun openRow(r: Row) {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SLUG, r.slug)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun rowMenu(anchor: android.view.View, r: Row) {
        val read = prefs.isRead(r.slug)
        val starred = prefs.isBookmarked(r.slug)
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (read) "Mark unread" else "Mark read")
            menu.add(0, 2, 1, if (starred) "Remove bookmark" else "Bookmark")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> prefs.setRead(r.slug, !read)
                    2 -> {
                        prefs.toggleBookmark(r.slug, "${r.code} — ${r.title}")
                        if (tab == TAB_BOOKMARKS) { load(); return@setOnMenuItemClickListener true }
                    }
                }
                refilter()
                true
            }
            show()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var rows: List<Row> = emptyList()
        private var bookmarked: Set<String> = emptySet()
        private var read: Set<String> = emptySet()

        fun submit(newRows: List<Row>) {
            rows = newRows
            refreshMarks()
        }

        fun refreshMarks() {
            bookmarked = prefs.bookmarks().map { it.slug }.toSet()
            read = prefs.readSet()
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemEntryBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.v.code.text = r.code.removePrefix("SCP-").let { if (r.code.startsWith("SCP-")) it else r.code }
            h.v.title.text = r.title
            h.v.sub.text = if (r.isJump) "Tap to open" else r.sub
            h.v.sub.isVisible = r.isJump || r.sub != null
            h.v.check.isVisible = r.slug in read
            h.v.star.isVisible = r.slug in bookmarked
            h.v.root.alpha = if (r.slug in read && tab != TAB_HISTORY) 0.6f else 1f
            h.v.root.setOnClickListener { openRow(r) }
            h.v.root.setOnLongClickListener { rowMenu(it, r); true }
        }
    }
}
