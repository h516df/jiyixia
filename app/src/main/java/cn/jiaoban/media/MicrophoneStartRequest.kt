package cn.jiaoban.media

/** Permission callbacks can arrive before ON_RESUME. A grant is not a denial in that window.
 * Only an explicit Record tap can arm this request; a normal resume never auto-starts audio. */
class MicrophoneStartRequest {
    private var pending = false
    fun granted(isResumed: Boolean): Boolean { pending = !isResumed; return isResumed }
    fun resumed(hasPermission: Boolean): Boolean {
        val start = pending && hasPermission
        pending = false
        return start
    }
    fun cancel() { pending = false }
}
