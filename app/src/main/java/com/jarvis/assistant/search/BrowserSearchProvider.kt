package com.jarvis.assistant.search

import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.command.ActivityLauncher

class BrowserSearchProvider(private val launcher: ActivityLauncher) : WebSearchProvider {
    override val id = "browser"

    override suspend fun search(query: String): SearchResults {
        val uri = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", query).build()
        val outcome = launcher.launch(Intent(Intent.ACTION_VIEW, uri), "Search: $query")
        if (outcome == ActivityLauncher.Outcome.FAILED) throw SearchException("No browser available")
        return SearchResults(query, emptyList(), openedInBrowser = true)
    }
}
