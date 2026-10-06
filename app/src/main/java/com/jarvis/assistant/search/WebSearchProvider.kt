package com.jarvis.assistant.search

data class SearchSnippet(val title: String, val text: String, val source: String)

data class SearchResults(
    val query: String,
    val snippets: List<SearchSnippet>,
    /** True when the provider handed the query to the browser instead of returning data. */
    val openedInBrowser: Boolean = false,
)

class SearchException(message: String) : Exception(message)

/**
 * Web search abstraction. [BrowserSearchProvider] opens the user's browser; [InstantAnswerSearchProvider]
 * returns text snippets the AI can summarise. A Brave / SerpAPI / Bing provider only has to implement this.
 */
interface WebSearchProvider {
    val id: String

    @Throws(SearchException::class)
    suspend fun search(query: String): SearchResults
}
