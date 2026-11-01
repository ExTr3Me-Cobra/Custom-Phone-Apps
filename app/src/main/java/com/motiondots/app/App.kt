package com.motiondots.app

import android.app.Application

/** Saves the details of a crash, shown (with Copy) the next time the app opens. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                getSharedPreferences("motion_dots", MODE_PRIVATE).edit()
                    .putString("crash", "Motion Dots $version, ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n${error.stackTraceToString()}")
                    .putBoolean("on", false) // don't crash again on restart
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
