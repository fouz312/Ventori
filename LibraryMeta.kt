package com.vantora.app.library

import com.vantora.app.compat.PeInfo
import com.vantora.app.compat.PeMachine
import com.vantora.app.compat.WinAppInfo
import com.vantora.app.core.GuestArch
import com.vantora.app.core.LibraryEntry
import com.vantora.app.media.MediaInfo
import org.json.JSONArray
import org.json.JSONObject

data class MediaSummary(val format: String, val arch: GuestArch, val label: String?, val windowsLike: Boolean, val notes: List<String>)

/** (De)serialises per-entry analysis results into LibraryEntry.metaJson. */
object LibraryMeta {
    fun mediaJson(base: JSONObject, m: MediaInfo): JSONObject = base
        .put("format", m.format.name).put("arch", m.arch.name).put("label", m.volumeLabel ?: "")
        .put("windowsLike", m.windowsLike).put("notes", JSONArray(m.notes))

    fun peJson(base: JSONObject, p: PeInfo, isMsi: Boolean): JSONObject = base
        .put("isMsi", isMsi).put("machine", p.machine.name).put("subsystem", p.subsystem)
        .put("dotnet", p.isDotNet).put("imports", JSONArray(p.imports.take(60)))
        .put("tags", JSONArray(p.tags.toList()))

    fun media(e: LibraryEntry): MediaSummary {
        val j = JSONObject(e.metaJson)
        val notes = j.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val arch = runCatching { GuestArch.valueOf(j.optString("arch", "UNKNOWN")) }.getOrDefault(GuestArch.UNKNOWN)
        return MediaSummary(j.optString("format", "UNKNOWN"), arch, j.optString("label").ifBlank { null }, j.optBoolean("windowsLike"), notes)
    }

    fun win(e: LibraryEntry): WinAppInfo {
        val j = JSONObject(e.metaJson)
        fun list(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val machine = runCatching { PeMachine.valueOf(j.optString("machine", "UNKNOWN")) }.getOrDefault(PeMachine.UNKNOWN)
        return WinAppInfo(e.displayName, j.optBoolean("isMsi"), machine, j.optInt("subsystem"),
            j.optBoolean("dotnet"), list("imports"), list("tags").toSet())
    }

    fun args(e: LibraryEntry): Pair<String, String> {
        val j = JSONObject(e.metaJson)
        return j.optString("args") to j.optString("env")
    }

    fun withConfig(e: LibraryEntry, args: String, env: String): String =
        JSONObject(e.metaJson).put("args", args).put("env", env).toString()
}
