# Keep ALL GamerVoice application classes, methods, singletons, and members
-keep class com.gamervoice.app.** { *; }
-keepclassmembers class com.gamervoice.app.** { *; }
-keepclasseswithmembers class com.gamervoice.app.** { *; }
-dontwarn com.gamervoice.app.**

# Keep ALL WebRTC classes, interfaces, enums, fields, and native JNI methods
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepclasseswithmembers class org.webrtc.** {
    native <methods>;
}
-dontwarn org.webrtc.**

# Keep inner classes and annotations essential for WebRTC JNI & reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# ViewBinding and AndroidX View Keep Rules
-keep class androidx.viewbinding.** { *; }
-keep class * implements androidx.viewbinding.ViewBinding { *; }
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(...);
}

# AndroidX & Material Components
-keep class com.google.android.material.** { *; }
-keepclassmembers class com.google.android.material.** { *; }
-keep class androidx.appcompat.** { *; }
-keepclassmembers class androidx.appcompat.** { *; }
-keep class androidx.constraintlayout.** { *; }
-keepclassmembers class androidx.constraintlayout.** { *; }
-dontwarn com.google.android.material.**

# OkHttp ProGuard Rules
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Razorpay Checkout Rules
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
-dontwarn com.razorpay.**
-keep class com.razorpay.** { *; }
-optimizations !method/inlining/*
-keepclasseswithmembers class * { public void onPayment*(...); }

# Google Mobile Ads & User Messaging Platform (UMP)
-keep class com.google.android.gms.ads.** { *; }
-keep interface com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-keep interface com.google.android.ump.** { *; }
-dontwarn com.google.android.gms.ads.**
-dontwarn com.google.android.ump.**

# Google Play Services & DataTransport
-keep class com.google.android.gms.** { *; }
-keepclassmembers class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
-keep class com.google.android.datatransport.** { *; }
-keepclassmembers class com.google.android.datatransport.** { *; }
-dontwarn com.google.android.datatransport.**

# Firebase Cloud Messaging, Crashlytics & Core
-keep class com.google.firebase.** { *; }
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**





