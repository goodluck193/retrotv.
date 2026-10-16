package com.retrotv.emu

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

object AppExit {
    fun finish(activity: Activity) {
        CoverArt.clearMemory()
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION") // RecentTaskInfo.id also exists on Android 8 / API 26.
        val task = manager.appTasks.firstOrNull { it.taskInfo.id == activity.taskId }
        if (task != null) task.finishAndRemoveTask() else activity.finishAffinity()
    }
}
