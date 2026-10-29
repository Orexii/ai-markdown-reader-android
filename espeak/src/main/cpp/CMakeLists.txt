cmake_minimum_required(VERSION 3.18)
project(espeakjni C)

# eSpeak NG (GPL-3.0-or-later), pinned as a submodule at the revision whose English data and phonemes
# match the phonemizer package used by the VS Code extension.
set(ESPEAK_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/espeak-ng)
set(ESPEAK_SRC ${ESPEAK_ROOT}/src/libespeak-ng)
set(UCD_SRC ${ESPEAK_ROOT}/src/ucd-tools/src)

add_library(espeakjni SHARED
  espeak_jni.c
  ${ESPEAK_SRC}/common.c
  ${ESPEAK_SRC}/mnemonics.c
  ${ESPEAK_SRC}/error.c
  ${ESPEAK_SRC}/ieee80.c
  ${ESPEAK_SRC}/compiledata.c
  ${ESPEAK_SRC}/compiledict.c
  ${ESPEAK_SRC}/dictionary.c
  ${ESPEAK_SRC}/encoding.c
  ${ESPEAK_SRC}/intonation.c
  ${ESPEAK_SRC}/langopts.c
  ${ESPEAK_SRC}/numbers.c
  ${ESPEAK_SRC}/phoneme.c
  ${ESPEAK_SRC}/phonemelist.c
  ${ESPEAK_SRC}/readclause.c
  ${ESPEAK_SRC}/setlengths.c
  ${ESPEAK_SRC}/soundicon.c
  ${ESPEAK_SRC}/spect.c
  ${ESPEAK_SRC}/ssml.c
  ${ESPEAK_SRC}/synthdata.c
  ${ESPEAK_SRC}/synthesize.c
  ${ESPEAK_SRC}/tr_languages.c
  ${ESPEAK_SRC}/translate.c
  ${ESPEAK_SRC}/translateword.c
  ${ESPEAK_SRC}/voices.c
  ${ESPEAK_SRC}/wavegen.c
  ${ESPEAK_SRC}/speech.c
  ${ESPEAK_SRC}/espeak_api.c
  ${UCD_SRC}/case.c
  ${UCD_SRC}/categories.c
  ${UCD_SRC}/ctype.c
  ${UCD_SRC}/proplist.c
  ${UCD_SRC}/scripts.c
  ${UCD_SRC}/tostring.c
)

target_include_directories(espeakjni PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}/config
  ${ESPEAK_ROOT}/src/include
  ${ESPEAK_ROOT}/src/include/compat
  ${ESPEAK_SRC}
  ${UCD_SRC}/include
)
target_compile_definitions(espeakjni PRIVATE LIBESPEAK_NG_EXPORT=1 "PATH_ESPEAK_DATA=\"/nonexistent\"")
target_compile_options(espeakjni PRIVATE -fvisibility=hidden -fno-exceptions -fwrapv -O2 -w)

if(ANDROID)
  target_link_libraries(espeakjni PRIVATE m)
  # 16 KB page alignment, required on Google Play for 64-bit devices.
  target_link_options(espeakjni PRIVATE "-Wl,-z,max-page-size=16384")
else()
  # Host build for JVM tests. Only the JNI headers are needed (FindJNI also demands AWT).
  find_path(JNI_HEADER_DIR jni.h PATHS $ENV{JAVA_HOME}/include REQUIRED NO_DEFAULT_PATH)
  target_include_directories(espeakjni PRIVATE ${JNI_HEADER_DIR} ${JNI_HEADER_DIR}/linux ${JNI_HEADER_DIR}/darwin)
  target_link_libraries(espeakjni PRIVATE m)
endif()
