// JNI access to eSpeak NG phonemes. Mirrors synth_ipa_ in espeak-ng's emscripten glue, which the
// phonemizer package (used by the VS Code extension) calls: the phoneme trace written while
// synthesizing, in IPA, one line per clause. Calls must be serialized by the caller.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <espeak-ng/speak_lib.h>

static int initialized = 0;

static jbyteArray to_bytes(JNIEnv *env, const char *text, size_t length) {
  jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
  if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)text);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_orexii_aimarkdownreader_core_espeak_EspeakNative_initialize(JNIEnv *env, jclass type, jstring dataPath) {
  (void)type;
  if (initialized) return 0;
  const char *path = (*env)->GetStringUTFChars(env, dataPath, NULL);
  int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 100, path, espeakINITIALIZE_DONT_EXIT);
  (*env)->ReleaseStringUTFChars(env, dataPath, path);
  if (rate <= 0) return -1;
  initialized = 1;
  return 0;
}

/** Returns the IPA for UTF-8 text, clauses separated by newlines, or null on failure. */
JNIEXPORT jbyteArray JNICALL
Java_com_orexii_aimarkdownreader_core_espeak_EspeakNative_phonemize(JNIEnv *env, jclass type, jbyteArray text, jstring voice) {
  (void)type;
  if (!initialized) return NULL;
  // phonemizer selects the voice before every call; doing the same keeps the output identical.
  const char *name = (*env)->GetStringUTFChars(env, voice, NULL);
  int ok = espeak_SetVoiceByName(name) == EE_OK;
  (*env)->ReleaseStringUTFChars(env, voice, name);
  if (!ok) return NULL;

  jsize length = (*env)->GetArrayLength(env, text);
  char *input = malloc((size_t)length + 1);
  if (!input) return NULL;
  (*env)->GetByteArrayRegion(env, text, 0, length, (jbyte *)input);
  input[length] = 0;

  char *output = NULL;
  size_t size = 0;
  FILE *trace = open_memstream(&output, &size);
  if (!trace) {
    free(input);
    return NULL;
  }
  espeak_SetSynthCallback(NULL);
  espeak_SetPhonemeTrace(espeakPHONEMES_IPA, trace);
  espeak_Synth(input, (size_t)length + 1, 0, POS_CHARACTER, 0, espeakCHARS_AUTO, NULL, NULL);
  espeak_SetPhonemeTrace(0, NULL);
  fclose(trace);
  free(input);

  jbyteArray result = to_bytes(env, output ? output : "", output ? size : 0);
  free(output);
  return result;
}
