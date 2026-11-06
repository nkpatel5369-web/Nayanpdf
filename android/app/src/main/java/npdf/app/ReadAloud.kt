package npdf.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import npdf.engine.DocInfo

/**
 * Read Out Loud with the phone's own text-to-speech voices (installed voices
 * work offline; N PDF never downloads any). Reads from a page to the end of the
 * document, page by page.
 */
class ReadAloud(private val ctx: Context, private val scope: CoroutineScope, private val onState: (State, String) -> Unit) {

    enum class State { STARTING, SPEAKING, PAUSED, STOPPED, ERROR }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var session: DocSession? = null
    private var page = 0
    private var chunks: List<String> = emptyList()
    private var chunk = 0
    private var paused = false
    private val main = Handler(Looper.getMainLooper())

    fun start(s: DocSession, fromPage: Int) {
        session = s; page = fromPage; chunk = 0; paused = false
        onState(State.STARTING, "Starting...")
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                main.post {
                    if (status != TextToSpeech.SUCCESS) {
                        onState(State.ERROR, "No text-to-speech engine is available. Install one in Settings > System > Languages > Text-to-speech.")
                        return@post
                    }
                    val voices = try { tts?.voices?.filter { !it.isNetworkConnectionRequired } } catch (e: Exception) { null }
                    if (voices != null && voices.isEmpty()) {
                        onState(State.ERROR, "No offline speech voices are installed. Add one in Settings > System > Languages > Text-to-speech.")
                        return@post
                    }
                    ready = true
                    tts?.setOnUtteranceProgressListener(listener)
                    loadPage()
                }
            }
        } else if (ready) loadPage()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) {
            main.post {
                if (paused || session == null) return@post
                chunk++
                if (chunk < chunks.size) speak() else { page++; chunk = 0; loadPage() }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { main.post { onState(State.ERROR, "The speech engine stopped with an error.") } }
    }

    private fun loadPage() {
        val s = session ?: return
        if (page >= s.sizes.size) { stop(); return }
        scope.launch {
            val text = try { s.read { DocInfo.pageText(it, page + 1) } } catch (e: Exception) { "" }
            chunks = split(text)
            chunk = 0
            if (chunks.isEmpty()) { page++; loadPage() } else speak()
        }
    }

    private fun speak() {
        onState(State.SPEAKING, "Reading page ${page + 1}")
        tts?.speak(chunks[chunk], TextToSpeech.QUEUE_FLUSH, null, "p$page-c$chunk")
    }

    /** Sentences grouped into pieces the speech engine accepts (it limits the input length). */
    private fun split(text: String): List<String> {
        val clean = text.replace(Regex("-\\n"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val max = minOf(TextToSpeech.getMaxSpeechInputLength() - 100, 1500)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (sentence in clean.split(Regex("(?<=[.!?।])\\s+"))) {
            if (cur.length + sentence.length + 1 > max && cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
            if (sentence.length > max) sentence.chunked(max).forEach { out.add(it) } else { if (cur.isNotEmpty()) cur.append(' '); cur.append(sentence) }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    val currentPage get() = page

    fun pause() {
        paused = true
        tts?.stop()
        onState(State.PAUSED, "Paused on page ${page + 1}")
    }

    fun resume() {
        if (!paused) return
        paused = false
        if (chunks.isNotEmpty()) speak() else loadPage()
    }

    fun stop() {
        paused = false
        session = null
        tts?.stop()
        onState(State.STOPPED, "")
    }

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null; ready = false
    }
}
