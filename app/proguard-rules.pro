-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}

-keep class com.yrrider.getlob.data.** { *; }
-keep class com.yrrider.getlob.widget.** { *; }
-keep class com.yrrider.getlob.fcm.** { *; }
-keep class com.yrrider.getlob.worker.** { *; }

-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class androidx.glance.** { *; }

-keep class io.livekit.android.** { *; }
-keep class livekit.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn okio.**
-dontwarn org.webrtc.**
-dontwarn io.livekit.**
