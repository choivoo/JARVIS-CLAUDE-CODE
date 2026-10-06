package com.friday.assistant.search

import com.friday.assistant.util.Http
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class SearchHit(val title: String, val snippet: String, val url: String)

interface WebSearchProvider {
    /** Empty list means "nothing found"; throws only for transport errors. */
    suspend fun search(query: String, limit: Int = 5): List<SearchHit>
}

object SearchParsers {
    private val tag = Regex("<[^>]+>")

    private fun clean(s: String) = s.replace(tag, "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("\\s+".toRegex(), " ").trim()

    /** Parses DuckDuckGo's HTML-only results page. */
    fun parseDuckDuckGoHtml(html: String, limit: Int): List<SearchHit> {
        val link = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snip = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = link.findAll(html).toList()
        val snippets = snip.findAll(html).toList()
        return links.mapIndexedNotNull { i, m ->
            val title = clean(m.groupValues[2])
            if (title.isEmpty()) null else SearchHit(title, snippets.getOrNull(i)?.let { clean(it.groupValues[1]) }.orEmpty(), decodeRedirect(m.groupValues[1]))
        }.take(limit)
    }

    private fun decodeRedirect(href: String): String {
        val m = Regex("""[?&]uddg=([^&]+)""").find(href) ?: return href
        return runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(href)
    }

    /** Parses the MediaWiki `list=search` JSON. */
    fun parseWikipedia(json: String, host: String, limit: Int): List<SearchHit> {
        val arr = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until minOf(arr.length(), limit)).map { i ->
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            SearchHit(title, clean(o.optString("snippet")), "https://$host/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"))
        }
    }
}

/** Primary: DuckDuckGo HTML (fresh results). Secondary: Korean Wikipedia (stable JSON API). */
class DefaultWebSearchProvider(private val client: OkHttpClient = Http.client) : WebSearchProvider {
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val ddg = runCatching {
            SearchParsers.parseDuckDuckGoHtml(get("https://html.duckduckgo.com/html/?q=$q&kl=kr-kr"), limit)
        }.getOrDefault(emptyList())
        if (ddg.isNotEmpty()) return ddg
        return SearchParsers.parseWikipedia(
            get("https://ko.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&format=json&srlimit=$limit"),
            "ko.wikipedia.org", limit,
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "FRIDAY-Android/1.0").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw e
        }
    }
}
