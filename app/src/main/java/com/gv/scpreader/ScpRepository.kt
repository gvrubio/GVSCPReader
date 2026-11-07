package com.gv.scpreader

import android.content.Context
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** One row of a series index page, e.g. scp-173 / SCP-173 / "The Sculpture". */
data class Entry(val slug: String, val code: String, val title: String, val exists: Boolean)

data class Series(val id: String, val label: String, val page: String)

/**
 * Downloads and caches the official series index pages (scp-series, scp-series-2, ...),
 * and answers "what's next/previous/random" using that ordering.
 */
object ScpRepository {
    const val BASE = "https://scp-wiki.wikidot.com/"

    val SERIES: List<Series> = (1..10).map { n ->
        Series(n.toString(), "Series ${roman(n)}", if (n == 1) "scp-series" else "scp-series-$n")
    } + Series("j", "Joke", "joke-scps")

    private val mem = ConcurrentHashMap<String, List<Entry>>()

    fun urlFor(slug: String) = BASE + slug

    /** Converts user input like "173", "173-j", "SCP-049" into a page slug. */
    fun slugFromInput(input: String): String? {
        val s = input.trim().lowercase().removePrefix("scp").trim('-', ' ')
        val m = Regex("""^(\d{1,5})(-[a-z]+)?$""").find(s) ?: return null
        return "scp-" + pad(m.groupValues[1].toInt()) + m.groupValues[2]
    }

    fun pad(n: Int) = if (n < 1000) "%03d".format(n) else n.toString()

    fun seriesFor(slug: String): Series? {
        if (slug.endsWith("-j")) return SERIES.last()
        val n = Regex("""^scp-(\d+)$""").find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return SERIES.getOrNull(n / 1000)
    }

    /** Returns entries from memory or disk cache without touching the network. */
    fun cached(ctx: Context, s: Series): List<Entry>? {
        mem[s.id]?.let { return it }
        val f = cacheFile(ctx, s)
        if (!f.exists()) return null
        val list = f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) null else Entry(p[0], p[1], p[2], p[3] == "1")
        }
        if (list.isNotEmpty()) mem[s.id] = list
        return list.ifEmpty { null }
    }

    suspend fun load(ctx: Context, s: Series, force: Boolean = false): List<Entry> =
        withContext(Dispatchers.IO) {
            if (!force) cached(ctx, s)?.let { return@withContext it }
            val list = parse(fetch(urlFor(s.page)))
            if (list.isNotEmpty()) {
                mem[s.id] = list
                cacheFile(ctx, s).writeText(list.joinToString("\n") {
                    listOf(it.slug, it.code, it.title.replace('\t', ' '), if (it.exists) "1" else "0")
                        .joinToString("\t")
                })
            }
            list
        }

    fun find(ctx: Context, slug: String): Entry? =
        seriesFor(slug)?.let { s -> cached(ctx, s)?.firstOrNull { it.slug == slug } }

    /**
     * Next (dir=+1) or previous (dir=-1) written article. Uses the cached index so that
     * unwritten slots are skipped and joke SCPs work; falls back to plain numbering.
     */
    fun neighbor(ctx: Context, slug: String, dir: Int): String? {
        val s = seriesFor(slug) ?: return null
        val list = cached(ctx, s)
        if (list != null) {
            val i = list.indexOfFirst { it.slug == slug }
            if (i >= 0) {
                var j = i + dir
                while (j in list.indices) {
                    if (list[j].exists) return list[j].slug
                    j += dir
                }
                if (s.id == "j") return null
            }
        }
        val n = Regex("""^scp-(\d+)$""").find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val target = n + dir
        return if (target < 1) null else "scp-" + pad(target)
    }

    /** A random written main-series SCP, preferring the cached indexes. */
    fun randomSlug(ctx: Context): String {
        val pool = SERIES.dropLast(1).flatMap { cached(ctx, it).orEmpty() }.filter { it.exists }
        if (pool.isNotEmpty()) return pool.random().slug
        return "scp-" + pad((2..7999).random())
    }

    private fun cacheFile(ctx: Context, s: Series) = File(ctx.filesDir, "series_${s.id}.tsv")

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SCPNightReader/1.0")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private val LI = Regex("""<li>\s*(?:<(?:strong|em|span)[^>]*>\s*)*<a([^>]*)>(.*?)</a>(.*?)</li>""",
        RegexOption.DOT_MATCHES_ALL)
    private val HREF = Regex("""href="/(scp-[^"/#?]+)"""")
    private val TAG = Regex("<[^>]+>")
    private val ARTICLE_SLUG = Regex("""^scp-\d+$""")

    internal fun parse(html: String): List<Entry> {
        // Only look inside the article body so sidebar links don't leak in.
        val start = html.indexOf("id=\"page-content\"").coerceAtLeast(0)
        val end = html.indexOf("id=\"page-info\"", start).let { if (it < 0) html.length else it }
        val body = html.substring(start, end)
        val out = LinkedHashMap<String, Entry>()
        for (m in LI.findAll(body)) {
            val attrs = m.groupValues[1]
            val slug = HREF.find(attrs)?.groupValues?.get(1) ?: continue
            // Filter on the slug, not the label: some entries are styled "VNP-8050", "PPC-8068", etc.
            if (!ARTICLE_SLUG.matches(slug) && !slug.endsWith("-j")) continue
            val code = text(m.groupValues[2])
            val title = text(m.groupValues[3]).trimStart(' ', '-', '–', '—', ':').trim()
            if (slug !in out) out[slug] = Entry(slug, code, title, !attrs.contains("newpage"))
        }
        return out.values.toList()
    }

    private fun text(html: String) =
        HtmlCompat.fromHtml(TAG.replace(html, ""), HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace('\n', ' ').trim()

    private fun roman(n: Int) = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")[n - 1]
}
