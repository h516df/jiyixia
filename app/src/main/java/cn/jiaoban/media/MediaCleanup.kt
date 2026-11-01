package cn.jiaoban.media

import android.content.Context
import cn.jiaoban.ImportedAttachment
import cn.jiaoban.Task

/**
 * Delete only files formerly referenced by a permanently purged record.
 * Failure is conservative: a questionable file is retained rather than losing
 * somebody else's photo or an unfinished recording.
 */
object MediaCleanup {
    fun prune(context:Context,candidates:List<ImportedAttachment>,records:List<Task>,pending:List<Task> = records,
        importing:Boolean = false):Int {
        if(candidates.isEmpty() || importing) return 0
        val app=context.applicationContext
        // An in-progress capture or a corrupted attachment draft has priority over space reclamation.
        if(VoiceSessionController.state.value.sessionId!=null) return 0
        val voice=VoiceSessionStore(app)
        if(voice.hasCorruptAttachmentIndex()) return 0
        val editor=app.getSharedPreferences("editor_draft_v1",Context.MODE_PRIVATE)
        val editorAssets=runCatching { AttachmentDraft.decode(editor.getString("attachments",null)) }
            .getOrElse { return 0 }
        val protected=(records+pending).flatMap { it.attachments }.map { it.relativePath }.toSet()+
            editorAssets.map { it.relativePath }+voice.read().attachments.map { it.relativePath }
        var removed=0
        // Both photo imports and voice segments use this publisher's lock.
        synchronized(AttachmentPublisher) {
            for(candidate in candidates.distinctBy { it.relativePath }) {
                if(candidate.relativePath in protected) continue
                // A draft can change while the user is still viewing a recycle-bin screen.
                val latestDraft=runCatching { AttachmentDraft.decode(editor.getString("attachments",null)) }
                    .getOrElse { return removed }
                if(latestDraft.any { it.relativePath==candidate.relativePath }) continue
                val file=runCatching { MediaStorage.file(app.filesDir,candidate.relativePath) }.getOrNull() ?: continue
                if(!file.isFile || runCatching { MediaStorage.hash(file) }.getOrNull()!=candidate.sha256) continue
                if(file.delete()) removed++
            }
        }
        return removed
    }
}
