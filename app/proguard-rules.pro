# Preserve Firestore models & annotations
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}

# Preserve data models, settings & widget state
-keep class com.yrrider.getlob.data.** { *; }
-keep class com.yrrider.getlob.widget.** { *; }

# Glance AppWidget & Receivers
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class androidx.glance.** { *; }

# LiveKit & WebRTC
-keep class io.livekit.android.** { *; }
-keep class livekit.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn okio.**
-dontwarn org.webrtc.**
-dontwarn io.livekit.**
