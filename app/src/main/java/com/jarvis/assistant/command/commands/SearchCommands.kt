package com.jarvis.assistant.command.commands

import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.AppResolver
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.search.SearchException
import com.jarvis.assistant.search.WebSearchProvider

/** "구글에서 ○○ 검색해줘": opens the browser search (BrowserSearchProvider). */
class SearchWebCommand(private val browser: WebSearchProvider) : Command {
    override val types = listOf("SEARCH_WEB", "WEB_SEARCH", "GOOGLE_SEARCH")

    override suspend fun execute(action: AiAction): ActionResult {
        val query = action.param("query") ?: return ActionResult.fail(
            "What would you like me to search for?",
            "무엇을 검색할까요?",
        )
        return try {
            browser.search(query)
            ActionResult.ok()
        } catch (e: SearchException) {
            ActionResult.fail("I couldn't open a browser.", "브라우저를 열 수 없습니다.")
        }
    }
}

/** Looks facts up and hands the snippets to the AI for a spoken answer. */
class WebAnswerCommand(private val provider: WebSearchProvider) : Command {
    override val types = listOf("WEB_ANSWER", "LOOKUP", "WEB_LOOKUP")

    override suspend fun execute(action: AiAction): ActionResult {
        val query = action.param("query") ?: return ActionResult.fail(
            "What should I look up?",
            "무엇을 찾아볼까요?",
        )
        return try {
            val results = provider.search(query)
            val data = results.snippets.joinToString("\n\n") { "[${it.source}] ${it.title}: ${it.text}" }
            val first = results.snippets.first()
            ActionResult(
                success = true,
                speech = "Here is what I found: ${first.text.take(220)}",
                subtitle = first.text.take(220),
                data = "Query: $query\n$data",
                narrate = true,
            )
        } catch (e: SearchException) {
            ActionResult.fail(
                "I couldn't find anything useful online.",
                "온라인에서 유용한 정보를 찾지 못했습니다.",
            )
        }
    }
}

class SearchYoutubeCommand(
    private val resolver: AppResolver,
    private val launcher: ActivityLauncher,
) : Command {
    override val types = listOf("SEARCH_YOUTUBE", "YOUTUBE_SEARCH")

    override suspend fun execute(action: AiAction): ActionResult {
        val query = action.param("query") ?: return ActionResult.fail(
            "What should I search for on YouTube?",
            "유튜브에서 무엇을 검색할까요?",
        )
        val uri = Uri.parse("https://www.youtube.com/results").buildUpon()
            .appendQueryParameter("search_query", query).build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        // Prefer the YouTube app; without it the same link opens in the browser (fallback).
        if (resolver.youtubeInstalled()) intent.setPackage(AppResolver.YOUTUBE)
        return launcher.launch(intent, "YouTube: $query").toResult(
            ActionResult.fail("I couldn't open YouTube.", "유튜브를 열 수 없습니다."),
        )
    }
}
