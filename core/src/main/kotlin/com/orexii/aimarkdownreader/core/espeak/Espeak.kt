package com.orexii.aimarkdownreader.core.espeak

import com.orexii.aimarkdownreader.core.Phonemizer

/** JNI entry points of libespeakjni (espeak/src/main/cpp/espeak_jni.c). */
object EspeakNative {
    @JvmStatic external fun initialize(dataPath: String): Int

    @JvmStatic external fun phonemize(text: ByteArray, voice: String): ByteArray?

    @Volatile private var loaded = false

    /** Loads the library: the app's packaged one, or a host build (for JVM tests) when a path is given. */
    @Synchronized
    fun load(path: String? = null) {
        if (loaded) return
        if (path == null) System.loadLibrary("espeakjni") else System.load(path)
        loaded = true
    }
}

/**
 * eSpeak NG phonemes, as the extension's phonemizer package produces them. [dataPath] is the folder that
 * contains espeak-ng-data. eSpeak is not thread-safe, so calls are serialized.
 */
class EspeakPhonemizer(dataPath: String, libraryPath: String? = null) : Phonemizer {
    init {
        synchronized(LOCK) {
            EspeakNative.load(libraryPath)
            check(EspeakNative.initialize(dataPath) == 0) { "eSpeak NG could not start (data in $dataPath)." }
        }
    }

    override fun phonemize(text: String, language: String): List<String> = synchronized(LOCK) {
        val bytes = EspeakNative.phonemize(text.toByteArray(Charsets.UTF_8), language)
            ?: error("eSpeak NG could not phonemize the text with voice $language.")
        String(bytes, Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }
    }

    private companion object {
        val LOCK = Any()
    }
}
