# The reader page calls these through addJavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# JNI: eSpeak NG's entry points are looked up by class and method name.
-keep class com.orexii.aimarkdownreader.core.espeak.EspeakNative { *; }
# ONNX Runtime's Java API is called from its native library.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
