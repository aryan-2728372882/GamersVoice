# WebRTC ProGuard Rules
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp ProGuard Rules
-keepattributes Signature
-keepattributes Annotation
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
