package com.friday.assistant.command

import android.content.Context
import android.content.Intent

data class ResolvedApp(val label: String, val packageName: String)

/** Finds an installed, launchable app from a spoken name. */
class AppResolver(private val context: Context) {

    fun resolve(spoken: String): ResolvedApp? {
        val pm = context.packageManager
        val launchables = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { ResolvedApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        return match(spoken, launchables)
    }

    companion object {
        private val aliases: Map<String, List<String>> = mapOf(
            "유튜브" to listOf("com.google.android.youtube"), "유투브" to listOf("com.google.android.youtube"),
            "youtube" to listOf("com.google.android.youtube"),
            "유튜브뮤직" to listOf("com.google.android.apps.youtube.music"), "youtubemusic" to listOf("com.google.android.apps.youtube.music"),
            "카카오톡" to listOf("com.kakao.talk"), "카톡" to listOf("com.kakao.talk"),
            "크롬" to listOf("com.android.chrome"), "chrome" to listOf("com.android.chrome"),
            "네이버" to listOf("com.nhn.android.search"), "naver" to listOf("com.nhn.android.search"),
            "구글지도" to listOf("com.google.android.apps.maps"), "지도" to listOf("com.google.android.apps.maps"),
            "지메일" to listOf("com.google.android.gm"), "gmail" to listOf("com.google.android.gm"),
            "스포티파이" to listOf("com.spotify.music"), "spotify" to listOf("com.spotify.music"),
            "넷플릭스" to listOf("com.netflix.mediaclient"), "netflix" to listOf("com.netflix.mediaclient"),
            "인스타" to listOf("com.instagram.android"), "인스타그램" to listOf("com.instagram.android"),
            "텔레그램" to listOf("org.telegram.messenger"), "디스코드" to listOf("com.discord"),
            "플레이스토어" to listOf("com.android.vending"), "구글플레이" to listOf("com.android.vending"),
        )

        fun normalize(s: String) = s.lowercase().replace(Regex("[\\s._-]"), "")

        /** Pure matching so it can be unit-tested without a device. */
        fun match(spoken: String, installed: List<ResolvedApp>): ResolvedApp? {
            val q = normalize(spoken)
            if (q.isEmpty()) return null
            aliases[q]?.forEach { pkg -> installed.firstOrNull { it.packageName == pkg }?.let { return it } }
            installed.firstOrNull { normalize(it.label) == q }?.let { return it }
            installed.filter { normalize(it.label).contains(q) }.minByOrNull { it.label.length }?.let { return it }
            if (q.length >= 3) installed.filter { q.contains(normalize(it.label)) && normalize(it.label).length >= 2 }
                .maxByOrNull { it.label.length }?.let { return it }
            return null
        }
    }
}
