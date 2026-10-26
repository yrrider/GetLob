-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}

-keep class com.yrrider.getlob.data.** { *; }
-keep class com.yrrider.getlob.widget.** { *; }

-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class androidx.glance.** { *; }

-keep class io.livekit.android.** { *; }
-keep class livekit.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn okio.**
-dontwarn org.webrtc.**
-dontwarn io.livekit.**
