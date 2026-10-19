package cn.jiaoban.data

import android.content.SharedPreferences
import java.util.UUID

/** A durable quick-entry draft. A completed request may only clear its own exact draft. */
class WritingDraft(private val preferences: SharedPreferences) {
    data class Submission(val id: String, val text: String)
    val text: String get() = preferences.getString("text", "") ?: ""
    private val id: String? get() = preferences.getString("id", null)
    private var pending: Submission? = null

    fun retain(value: String) {
        val edit=preferences.edit().putString("text",value)
        // A new composition can accept another thought while the old writer finishes.
        if(preferences.contains("inflight") && value!=preferences.getString("inflight",null))
            edit.remove("id").remove("inflight")
        edit.apply()
    }
    fun begin(): Submission? {
        if (pending != null || text.isBlank()) return null
        return Submission(id ?: UUID.randomUUID().toString(), text).also {
            preferences.edit().putString("id", it.id).putString("text", it.text).putString("inflight",it.text).apply()
            pending = it
        }
    }
    fun finish(request: Submission, success: Boolean): Boolean {
        if (pending == request) pending = null
        if (id != request.id || text != request.text) return false
        if (!success) { preferences.edit().remove("inflight").apply(); return false }
        preferences.edit().remove("text").remove("id").remove("inflight").apply()
        return true
    }
    fun reconcile(saved: List<cn.jiaoban.Task>): Boolean {
        val currentId = id ?: return false
        if (saved.none { it.id == currentId && it.text == text.trim() }) return false
        preferences.edit().remove("text").remove("id").remove("inflight").apply()
        return true
    }
}
