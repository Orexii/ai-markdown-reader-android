package com.orexii.aimarkdownreader.reader

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.orexii.aimarkdownreader.audio.AudioTrackSink
import com.orexii.aimarkdownreader.core.EngineKind
import com.orexii.aimarkdownreader.core.ModelCatalog
import com.orexii.aimarkdownreader.core.Navigation
import com.orexii.aimarkdownreader.core.Pronounce
import com.orexii.aimarkdownreader.core.ReaderDocument
import com.orexii.aimarkdownreader.core.SpeechEngine
import com.orexii.aimarkdownreader.core.Speeds
import com.orexii.aimarkdownreader.core.player.NarrationPlayer
import com.orexii.aimarkdownreader.core.player.PlayerState
import com.orexii.aimarkdownreader.core.player.StartAt
import com.orexii.aimarkdownreader.data.EngineChoice
import com.orexii.aimarkdownreader.data.Library
import com.orexii.aimarkdownreader.data.ReaderSettings
import com.orexii.aimarkdownreader.data.RecentDocument
import com.orexii.aimarkdownreader.data.SavedPosition
import com.orexii.aimarkdownreader.data.SettingsStore
import com.orexii.aimarkdownreader.speech.ModelStore
import com.orexii.aimarkdownreader.speech.NaturalVoice
import com.orexii.aimarkdownreader.speech.SystemEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A document being read: its text and where it came from. */
data class OpenDocument(
    val uri: String,
    val title: String,
    val text: String,
    /** The folder (a document tree, or "samples") used for images and links next to the document. */
    val folder: String?,
)

/** The voice behind the player. */
sealed interface VoiceState {
    data object Off : VoiceState
    /** The natural voice is chosen but not downloaded yet. */
    data object NeedsDownload : VoiceState
    data object Loading : VoiceState
    data class Ready(val kind: EngineKind) : VoiceState
    data class Failed(val message: String) : VoiceState
}

/** What the reader page reports it highlights (used by the instrumented tests). */
data class PageState(val sentence: String, val word: String, val following: Boolean)

/**
 * Connects the reader page, the player, the voices and the settings. Lives as long as the app process, so
 * reading continues in the background. Mirrors the extension's src/reader.ts.
 */
class ReaderController(
    private val context: Context,
    private val scope: CoroutineScope,
    val settings: SettingsStore,
    val library: Library,
    val models: ModelStore,
) {
    val player = NarrationPlayer(AudioTrackSink()) { Log.i(TAG, it) }
    private val natural = NaturalVoice(context, models)

    private val documentFlow = MutableStateFlow<OpenDocument?>(null)
    val document: StateFlow<OpenDocument?> = documentFlow.asStateFlow()
    private val voiceFlow = MutableStateFlow<VoiceState>(VoiceState.Off)
    val voice: StateFlow<VoiceState> = voiceFlow.asStateFlow()
    val pageState = MutableStateFlow<PageState?>(null)
    /** Narrations the page has built, and whether the current one is a selection. */
    private var narration: ReaderDocument? = null
    private var selection = false
    /** Play as soon as the narration of the opened document arrives. */
    private var autoplay = false
    private var rebuilding = false

    private var engine: SpeechEngine? = null
    private var engineKey = ""
    private var loadJob: Job? = null
    private var appliedSettings: ReaderSettings = settings.settings.value

    init {
        scope.launch { settings.settings.collect(::applySettings) }
        scope.launch { models.changes.collect { if (voiceFlow.value == VoiceState.NeedsDownload || voiceFlow.value is VoiceState.Failed) ensureVoice() } }
        scope.launch { player.state.map { it.playing }.distinctUntilChanged().collect(::onPlayingChanged) }
        scope.launch { savePositions() }
    }

    val state: StateFlow<PlayerState> get() = player.state

    // Documents.

    /** Shows a document; the reader page renders it and sends its narration to [onNarration]. */
    fun open(document: OpenDocument, play: Boolean = false) {
        val same = documentFlow.value?.uri == document.uri && documentFlow.value?.text == document.text
        if (!same) {
            player.unload()
            narration = null
            selection = false
            pageState.value = null
        }
        documentFlow.value = document
        autoplay = play
        scope.launch { library.opened(RecentDocument(document.uri, document.title, document.folder, System.currentTimeMillis())) }
        ensureVoice()
    }

    /** The narration the page built: for a new document, after a setting changed, or for a selection. */
    fun onNarration(json: String, isSelection: Boolean) {
        val doc = runCatching { ReaderDocument.parse(json) }.getOrElse {
            Log.e(TAG, "Bad narration", it)
            return
        }
        val document = documentFlow.value ?: return
        val previous = narration
        val state = player.state.value
        narration = doc
        if (doc.utterances.isEmpty()) {
            player.unload()
            return
        }
        when {
            isSelection -> {
                selection = true
                player.load(doc.utterances, doc.blocks, document.title, true, StartAt(autoplay = true))
            }
            rebuilding && previous != null && !selection -> {
                // A setting changed: keep the listener's place (reader.ts rebuild).
                rebuilding = false
                val utterance = previous.utterances.getOrNull(state.index)
                val place = utterance?.words?.getOrNull(maxOf(0, state.word))?.range ?: utterance?.range
                val (index, word) = if (place != null) Navigation.locate(doc.utterances, place.line, place.character)
                else (doc.blocks.getOrNull(utterance?.block ?: -1)?.first?.coerceAtLeast(0) ?: 0) to -1
                player.load(doc.utterances, doc.blocks, document.title, false, StartAt(index, 0.0, word, state.playing))
            }
            else -> {
                rebuilding = false
                selection = false
                val play = autoplay
                autoplay = false
                scope.launch {
                    val start = resumeAt(document.uri, doc)
                    player.load(doc.utterances, doc.blocks, document.title, false, StartAt(start, autoplay = play))
                }
            }
        }
    }

    /** Where reading stopped last time (extension: startAt 'resume'). */
    private suspend fun resumeAt(uri: String, doc: ReaderDocument): Int {
        val saved = library.position(uri) ?: return 0
        val utterances = doc.utterances
        val index = if (utterances.getOrNull(saved.index)?.line == saved.line) saved.index else Navigation.atLine(utterances, saved.line)
        return if (index >= utterances.size - 1) 0 else index
    }

    /** The text of a word of the current narration (the instrumented tests compare it with the page's highlight). */
    fun expectedWord(index: Int, word: Int): String? = narration?.utterances?.getOrNull(index)?.words?.getOrNull(word)?.text

    /** Removes a document from the recent list. */
    fun forget(uri: String) {
        scope.launch { library.forget(uri) }
    }

    /** The page is about to rebuild the narration because a setting changed. */
    fun rebuildRequested() {
        rebuilding = true
    }

    /** Double-tap on a word, or "Read aloud from here". */
    fun readFrom(line: Int, character: Int) {
        val doc = narration ?: return
        if (selection) return
        val (index, word) = Navigation.locate(doc.utterances, line, character)
        player.seekWord(index, word, play = true)
        if (engine == null) ensureVoice()
    }

    fun close() {
        player.pause()
        player.unload()
        documentFlow.value = null
        narration = null
    }

    // Playback commands (buttons, keys, the media session).

    fun command(action: String) {
        val s = settings.settings.value
        when (action) {
            "togglePlay" -> if (player.state.value.playing) player.pause() else play()
            "play" -> play()
            "pause", "stop" -> player.pause()
            "skipBack" -> player.skip(-s.skipBack.toDouble())
            "skipForward" -> player.skip(s.skipForward.toDouble())
            "previousSentence" -> player.previousSentence()
            "nextSentence" -> player.nextSentence()
            "previousParagraph" -> player.previousParagraph()
            "nextParagraph" -> player.nextParagraph()
            "previousSection" -> player.previousSection()
            "nextSection" -> player.nextSection()
            "faster" -> setRate(Speeds.faster(player.state.value.rate, s.expandedSpeeds))
            "slower" -> setRate(Speeds.slower(player.state.value.rate, s.expandedSpeeds))
            "top" -> player.top()
        }
    }

    fun play() {
        if (voiceFlow.value !is VoiceState.Ready) ensureVoice()
        player.play()
    }

    fun setRate(rate: Double) {
        player.setRate(rate)
        scope.launch { settings.update { it.copy(rate = Speeds.nearest(rate)) } }
    }

    fun update(change: (ReaderSettings) -> ReaderSettings) {
        scope.launch { settings.update(change) }
    }

    // Voices.

    fun downloadNaturalVoice(quality: ModelCatalog.Quality) {
        update { it.copy(engine = EngineChoice.NATURAL, quality = quality) }
        models.install(quality, settings.settings.value.voice)
    }

    fun useSystemVoice() = update { it.copy(engine = EngineChoice.SYSTEM) }

    fun useNaturalVoice() = update { it.copy(engine = EngineChoice.NATURAL) }

    fun removeNaturalVoice() {
        if (engine?.kind == EngineKind.NATURAL) replaceEngine(null, "", VoiceState.NeedsDownload)
        natural.clear()
        models.remove()
    }

    private fun dictionary(s: ReaderSettings) = Pronounce.dictionaryFrom(s.pronunciations)

    private fun applySettings(s: ReaderSettings) {
        val before = appliedSettings
        appliedSettings = s
        if (player.state.value.rate != s.rate) player.setRate(s.rate)
        val voiceChanged = before.engine != s.engine || before.quality != s.quality || before.threads != s.threads ||
            before.voice != s.voice || before.systemVoice != s.systemVoice || before.pronunciations != s.pronunciations
        if (voiceChanged && (engine != null || voiceFlow.value != VoiceState.Off)) ensureVoice()
    }

    /** Makes the chosen voice ready, loading or switching engines as needed. */
    fun ensureVoice() {
        val s = settings.settings.value
        val key = when (s.engine) {
            EngineChoice.NATURAL -> "natural:${s.quality}:${s.threads}"
            EngineChoice.SYSTEM -> "system"
        }
        val voiceId = if (s.engine == EngineChoice.NATURAL) s.voice else s.systemVoice
        if (key == engineKey && engine != null) {
            // Same engine: only the voice or the dictionary changed.
            scope.launch {
                if (s.engine == EngineChoice.NATURAL && !models.hasVoice(voiceId)) {
                    runCatching { models.installVoice(voiceId) }.onFailure { voiceFlow.value = VoiceState.Failed("Could not download the voice: ${it.message}") }
                }
                player.setEngine(engine, voiceId, dictionary(s))
            }
            return
        }
        if (s.engine == EngineChoice.NATURAL && (!models.hasModel(s.quality) || !models.hasVoice(s.voice))) {
            if (!models.hasModel(s.quality) && models.download.value !is ModelStore.Download.Running) {
                replaceEngine(null, "", VoiceState.NeedsDownload)
                return
            }
            if (models.hasModel(s.quality) && !models.hasVoice(s.voice)) {
                scope.launch {
                    runCatching { models.installVoice(s.voice) }.onSuccess { ensureVoice() }
                        .onFailure { voiceFlow.value = VoiceState.Failed("Could not download the voice: ${it.message}") }
                }
            }
            voiceFlow.value = if (models.download.value is ModelStore.Download.Running) VoiceState.NeedsDownload else VoiceState.Loading
            return
        }
        if (loadJob?.isActive == true && key == pendingKey) return
        pendingKey = key
        loadJob?.cancel()
        voiceFlow.value = VoiceState.Loading
        loadJob = scope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    when (s.engine) {
                        EngineChoice.NATURAL -> natural.create(s.quality, s.threads)
                        EngineChoice.SYSTEM -> SystemEngine.create(context)
                    }
                }
            }
            created.onSuccess { replaceEngine(it, key, VoiceState.Ready(it.kind)) }
                .onFailure {
                    Log.e(TAG, "Voice failed", it)
                    replaceEngine(null, "", VoiceState.Failed(it.message ?: "The voice could not start."))
                }
        }
    }

    private var pendingKey = ""

    private fun replaceEngine(next: SpeechEngine?, key: String, state: VoiceState) {
        val old = engine
        val s = settings.settings.value
        engine = next
        engineKey = key
        player.setEngine(next, if (next?.kind == EngineKind.SYSTEM) s.systemVoice else s.voice, dictionary(s))
        voiceFlow.value = state
        // Closing waits for a running synthesis to finish, so do it off the main thread.
        if (old != null && old !== next) scope.launch(Dispatchers.IO) { old.close() }
    }

    /** The phone's own voices, for the voice menu when the system voice is used. */
    fun systemVoices(): List<Pair<String, String>> = (engine as? SystemEngine)?.voices()?.map { it.name to "${it.locale.displayName} · ${it.name}" } ?: emptyList()

    // Positions.

    private suspend fun savePositions() {
        var last = PlayerState()
        player.state.collect { state ->
            val document = documentFlow.value
            val doc = narration
            val moved = state.index != last.index || state.playing != last.playing || state.finished != last.finished
            last = state
            if (document == null || doc == null || selection || !state.loaded || !moved) return@collect
            val utterance = doc.utterances.getOrNull(state.index) ?: return@collect
            val finished = state.finished || (state.index >= doc.utterances.size - 1 && !state.playing)
            library.savePosition(document.uri, if (finished) null else SavedPosition(utterance.line, state.index, doc.utterances.size, System.currentTimeMillis()))
        }
    }

    // Audio focus, headphones and keeping the CPU awake while reading with the screen off.

    private val audio = context.getSystemService(AudioManager::class.java)
    private var resumeOnFocus = false
    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnFocus = false
                        player.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (player.state.value.playing) {
                            resumeOnFocus = true
                            player.pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                        resumeOnFocus = false
                        player.play()
                    }
                }
            }
            .build()
    } else null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause()
        }
    }
    private var noisyRegistered = false

    @SuppressLint("WakelockTimeout")
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiMarkdownReader:reading").apply { setReferenceCounted(false) }

    private fun onPlayingChanged(playing: Boolean) {
        if (playing) {
            if (focusRequest != null && audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                player.pause()
                return
            }
            if (!noisyRegistered) {
                ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                noisyRegistered = true
            }
            wakeLock.acquire()
        } else {
            if (noisyRegistered) {
                context.unregisterReceiver(noisy)
                noisyRegistered = false
            }
            if (wakeLock.isHeld) wakeLock.release()
            if (!resumeOnFocus && focusRequest != null) {
                // Give the focus back after a short while, so a quick pause and play keeps it.
                scope.launch {
                    delay(30_000)
                    if (!player.state.value.playing && !resumeOnFocus) audio.abandonAudioFocusRequest(focusRequest)
                }
            }
        }
    }

    companion object {
        const val TAG = "AiMarkdownReader"
    }
}
