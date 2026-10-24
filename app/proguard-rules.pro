-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}
-keep class com.yrrider.getlob.data.** { *; }
-dontwarn okio.**
-dontwarn org.webrtc.**
-dontwarn io.livekit.**
