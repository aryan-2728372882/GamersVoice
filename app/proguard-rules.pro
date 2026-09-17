# Keep ALL GamerVoice application classes, methods, singletons, and members
-keep class com.gamervoice.app.** { *; }
-keepclassmembers class com.gamervoice.app.** { *; }
-keepclasseswithmembers class com.gamervoice.app.** { *; }
-dontwarn com.gamervoice.app.**

-keep class com.appforgamers.gamersvoice.** { *; }
-keepclassmembers class com.appforgamers.gamersvoice.** { *; }
-keepclasseswithmembers class com.appforgamers.gamersvoice.** { *; }
-dontwarn com.appforgamers.gamersvoice.**

-keepclassmembers class * implements android.os.Parcelable { static ** CREATOR; }
-keepclassmembers class * implements java.io.Serializable { *; }

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
-dontwarn com.google.android.material.**
-dontwarn androidx.**

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

# Google Play Services & Firebase
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.datatransport.**
-dontwarn com.google.firebase.**






