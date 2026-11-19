package com.yrrider.getlob.fcm

import kotlinx.coroutines.flow.MutableStateFlow

object AppForegroundTracker {
    val isChatVisible = MutableStateFlow(false)
}
