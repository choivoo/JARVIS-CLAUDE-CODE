package com.jarvis.assistant.command

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class ResolvedApp(val packageName: String, val label: String, val launchIntent: Intent)

/** Finds an installed app from an AI-supplied package name and/or a spoken app name. */
class AppResolver(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun isInstalled(packageName: String): Boolean = pm.getLaunchIntentForPackage(packageName) != null

    fun resolve(packageName: String?, spokenName: String?): ResolvedApp? {
        packageName?.takeIf { it.isNotBlank() }?.let { pkg -> fromPackage(pkg)?.let { return it } }
        val name = spokenName?.trim().orEmpty()
        if (name.isEmpty()) return null
        val key = normalize(name)
        aliases.entries.firstOrNull { (alias, _) -> key == alias || key.contains(alias) && alias.length >= 3 }
            ?.value?.forEach { pkg -> fromPackage(pkg)?.let { return it } }
        return searchByLabel(key)
    }

    fun youtubeInstalled() = isInstalled(YOUTUBE)

    private fun fromPackage(pkg: String): ResolvedApp? {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return null
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        return ResolvedApp(pkg, label, intent)
    }

    private fun searchByLabel(key: String): ResolvedApp? {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(main, 0).mapNotNull { info ->
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) null else Triple(normalize(label), label, pkg)
        }
        val best = candidates.firstOrNull { it.first == key }
            ?: candidates.firstOrNull { it.first.startsWith(key) }
            ?: candidates.firstOrNull { it.first.contains(key) }
            ?: candidates.firstOrNull { key.contains(it.first) && it.first.length >= 3 }
            ?: return null
        return fromPackage(best.third)?.copy(label = best.second)
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[\\s_\\-]+"), "")

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

        // Longest / most specific aliases first so "유튜브뮤직" wins over "유튜브".
        private val aliases: LinkedHashMap<String, List<String>> = linkedMapOf(
            "유튜브뮤직" to listOf(YOUTUBE_MUSIC),
            "youtubemusic" to listOf(YOUTUBE_MUSIC),
            "유튜브" to listOf(YOUTUBE),
            "youtube" to listOf(YOUTUBE),
            "크롬" to listOf("com.android.chrome"),
            "chrome" to listOf("com.android.chrome"),
            "구글지도" to listOf("com.google.android.apps.maps"),
            "googlemaps" to listOf("com.google.android.apps.maps"),
            "지도" to listOf("com.google.android.apps.maps", "com.nhn.android.nmap"),
            "maps" to listOf("com.google.android.apps.maps"),
            "지메일" to listOf("com.google.android.gm"),
            "gmail" to listOf("com.google.android.gm"),
            "카카오톡" to listOf("com.kakao.talk"),
            "kakaotalk" to listOf("com.kakao.talk"),
            "네이버" to listOf("com.nhn.android.search"),
            "카메라" to listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera"),
            "camera" to listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera"),
            "계산기" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"),
            "calculator" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"),
            "전화" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "갤러리" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
            "사진" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
            "플레이스토어" to listOf("com.android.vending"),
            "playstore" to listOf("com.android.vending"),
            "스포티파이" to listOf("com.spotify.music"),
            "spotify" to listOf("com.spotify.music"),
            "넷플릭스" to listOf("com.netflix.mediaclient"),
            "netflix" to listOf("com.netflix.mediaclient"),
        )
    }
}
