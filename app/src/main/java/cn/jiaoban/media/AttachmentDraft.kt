package cn.jiaoban.media

import android.content.SharedPreferences
import cn.jiaoban.ImportedAttachment
import org.json.JSONArray
import org.json.JSONObject

object AttachmentDraft {
    // A failed commit may still update SharedPreferences memory; do not mistake it for durable data.
    private val retryDurability = java.util.WeakHashMap<SharedPreferences, Boolean>()
    private fun commit(preferences: SharedPreferences, edit: SharedPreferences.Editor, message: String) {
        val success = edit.commit()
        if (success) retryDurability.remove(preferences) else retryDurability[preferences] = true
        check(success) { message }
    }
    fun encode(items: List<ImportedAttachment>): String = JSONArray().apply {
        items.forEach { a -> put(JSONObject().put("sha256", a.sha256).put("name", a.name)
            .put("relativePath", a.relativePath).put("mimeType", a.mimeType).put("sourceFile", a.sourceFile)) }
    }.toString()
    fun decode(raw: String?): List<ImportedAttachment> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            ImportedAttachment(it.getString("sha256"), it.getString("name"), it.getString("relativePath"), it.getString("mimeType"), it.optBoolean("sourceFile"))
        } }
    }
    /** Session identity and initial media are one durable edit; never expose a mixed draft. */
    @Synchronized fun open(preferences: SharedPreferences, session: String, existingId: String?, newId: String?, text: String, date: String?, seed: List<ImportedAttachment>) {
        if (preferences.getString("completed", null) == session) return
        val same = preferences.getString("session", null) == session
        if (same && retryDurability[preferences] != true && preferences.getBoolean("active", false) &&
            preferences.contains("attachments") && preferences.getString("existingId", null) == existingId &&
            preferences.getString("newId", null) == newId && preferences.getString("text", null) == text &&
            preferences.getString("date", null) == date) return
        val edit = preferences.edit().putBoolean("active", true).putString("session", session)
            .putString("existingId", existingId).putString("newId", newId).putString("text", text).putString("date", date)
        if (!same || !preferences.contains("attachments")) edit.putString("attachments", encode(seed))
        if (!same) edit.remove("completed").remove("saving").remove("savingProcess")
        commit(preferences, edit, "草稿保存失败，请保留页面并重试")
    }
    private fun requireCurrent(preferences: SharedPreferences, session: String) {
        check(preferences.getString("session", null) == session && preferences.getString("completed", null) != session) { "草稿已切换，原媒体仍保留" }
    }
    @Synchronized fun append(preferences: SharedPreferences, session: String, added: List<ImportedAttachment>): List<ImportedAttachment> {
        requireCurrent(preferences, session)
        val next = (decode(preferences.getString("attachments", null)) + added).distinctBy { it.relativePath }
        retain(preferences, next)
        return next
    }
    @Synchronized fun replace(preferences: SharedPreferences, session: String, items: List<ImportedAttachment>) {
        requireCurrent(preferences, session); retain(preferences, items)
    }
    @Synchronized fun clear(preferences: SharedPreferences, session: String) {
        if (preferences.getString("session", null) == session) commit(preferences, preferences.edit().clear(), "草稿未能关闭，请重试")
    }
    /** Caller holds this same lock for compound save-completion checks. */
    @Synchronized fun retain(preferences: SharedPreferences, items: List<ImportedAttachment>) {
        commit(preferences, preferences.edit().putString("attachments", encode(items)), "草稿保存失败，原附件仍保留，请重试")
    }
}
