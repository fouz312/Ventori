package com.vantora.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val latest: String, val url: String, val newer: Boolean)

/** Checks the GitHub Releases API of the repository you publish to. It only informs; installing stays manual. */
object UpdateManager {
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(repo: String, current: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(repo.matches(Regex("[\\w.-]+/[\\w.-]+"))) { "Enter the repository as owner/name" }
            val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 404) error("No published release found in $repo")
            if (c.responseCode != 200) error("GitHub answered HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.getString("tag_name")
            UpdateInfo(tag, j.getString("html_url"), isNewer(tag, current))
        }
    }
}
