# R8 Optimization Rules for GamerVoice

# Strip verbose & debug log statements in release build
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Keep ALL WebRTC classes, interfaces, enums, fields, and native JNI methods
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepclasseswithmembers class org.webrtc.** {
    native <methods>;
}
-dontwarn org.webrtc.**

# OkHttp ProGuard Rules
-keepattributes Signature
-keepattributes Annotation
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
