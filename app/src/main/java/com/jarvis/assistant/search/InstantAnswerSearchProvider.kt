package com.jarvis.assistant.search

import com.jarvis.assistant.util.Http
import com.jarvis.assistant.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Keyless search: DuckDuckGo instant answers + Wikipedia extracts, condensed into snippets. */
class InstantAnswerSearchProvider : WebSearchProvider {
    override val id = "instant-answer"

    override suspend fun search(query: String): SearchResults = coroutineScope {
        val ddg = async { runCatching { duckDuckGo(query) }.getOrNull() }
        val wiki = async { runCatching { wikipedia(query) }.getOrDefault(emptyList()) }
        val snippets = listOfNotNull(ddg.await()) + wiki.await()
        if (snippets.isEmpty()) throw SearchException("No results")
        SearchResults(query, snippets.take(4))
    }

    private suspend fun duckDuckGo(query: String): SearchSnippet? {
        val url = "https://api.duckduckgo.com/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        val json = JSONObject(get(url.toString()))
        val abstract = json.optString("AbstractText")
        if (abstract.isBlank()) return null
        return SearchSnippet(json.optString("Heading", query), abstract.take(700), "DuckDuckGo")
    }

    private suspend fun wikipedia(query: String): List<SearchSnippet> {
        val lang = if (query.any { it in '가'..'힣' }) "ko" else "en"
        val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrlimit", "3")
            .addQueryParameter("prop", "extracts")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exsentences", "3")
            .build()
        val pages = JSONObject(get(url.toString())).optJSONObject("query")?.optJSONObject("pages")
            ?: return emptyList()
        val list = mutableListOf<Pair<Int, SearchSnippet>>()
        for (key in pages.keys()) {
            val page = pages.getJSONObject(key)
            val extract = page.optString("extract")
            if (extract.isBlank()) continue
            list += page.optInt("index", 99) to SearchSnippet(page.optString("title"), extract.take(600), "Wikipedia")
        }
        return list.sortedBy { it.first }.map { it.second }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try {
            Http.client.await(Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.0").build())
                .use { response ->
                    if (!response.isSuccessful) throw SearchException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
        } catch (e: IOException) {
            throw SearchException("Search service unreachable")
        }
    }
}
