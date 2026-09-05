# R8 Optimization Rules for GamerVoice

# Strip verbose & debug log statements in release build for maximum performance
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# WebRTC ProGuard Rules (Keep JNI bindings and Audio Device Module)
-keep class org.webrtc.audio.** { *; }
-keep class org.webrtc.PeerConnection** { *; }
-keep class org.webrtc.PeerConnectionFactory** { *; }
-keep class org.webrtc.SessionDescription** { *; }
-keep class org.webrtc.IceCandidate** { *; }
-keep class org.webrtc.MediaConstraints** { *; }
-keep class org.webrtc.SdpObserver** { *; }
-keep class org.webrtc.RtpReceiver** { *; }
-keep class org.webrtc.RtpTransceiver** { *; }
-keep class org.webrtc.DataChannel** { *; }
-keep class org.webrtc.MediaStream** { *; }

# Keep native WebRTC JNI methods
-keepclasseswithmembernames,includedescriptorclasses class org.webrtc.** {
    native <methods>;
}

-dontwarn org.webrtc.**

# OkHttp ProGuard Rules
-keepattributes Signature
-keepattributes Annotation
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
