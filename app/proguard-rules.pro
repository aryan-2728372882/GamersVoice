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

# Keep GamerVoice WebRTC and Service classes for JNI callbacks and Binder
-keep class com.gamervoice.app.webrtc.** { *; }
-keepclassmembers class com.gamervoice.app.webrtc.** { *; }
-keep class com.gamervoice.app.service.** { *; }
-keepclassmembers class com.gamervoice.app.service.** { *; }

# Keep inner classes and annotations essential for WebRTC JNI
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# OkHttp ProGuard Rules
-keepattributes Signature
-keepattributes Annotation
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
