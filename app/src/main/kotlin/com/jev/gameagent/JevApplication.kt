package com.jev.gameagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.jev.gameagent.core.AgentCoordinator
import com.jev.gameagent.jev.JevApiClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class JevApplication : Application() {

    companion object {
        const val CHANNEL_ID_MEDIA_PROJECTION = "jev_media_projection_channel"
        const val CHANNEL_ID_OVERLAY = "jev_overlay_channel"
        lateinit var instance: JevApplication
            private set

        fun getCrashLog(context: Context): String? {
            val file = File(context.filesDir, "crash_log.txt")
            return if (file.exists()) file.readText() else null
        }

        fun clearCrashLog(context: Context) {
            val file = File(context.filesDir, "crash_log.txt")
            if (file.exists()) file.delete()
        }
    }

    lateinit var jevApiClient: JevApiClient
    lateinit var coordinator: AgentCoordinator

    override fun onCreate() {
        super.onCreate()
        instance = this

        setupCrashHandler()

        jevApiClient = JevApiClient(this)
        coordinator = AgentCoordinator(this, jevApiClient)

        createNotificationChannels()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()
                Log.e("JevCrash", "CRASH DETECTED on thread ${thread.name}: $stackTrace")

                val crashFile = File(filesDir, "crash_log.txt")
                crashFile.writeText("Time: ${Date()}\nThread: ${thread.name}\nException: ${throwable.javaClass.name}: ${throwable.message}\n\nStacktrace:\n$stackTrace")
            } catch (e: Exception) {
                Log.e("JevCrash", "Failed to write crash log", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val projectionChannel = NotificationChannel(
                CHANNEL_ID_MEDIA_PROJECTION,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps screen perception active during gameplay"
            }

            val overlayChannel = NotificationChannel(
                CHANNEL_ID_OVERLAY,
                "Agent Overlay Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays running state of the Jev autopilot"
            }

            notificationManager.createNotificationChannel(projectionChannel)
            notificationManager.createNotificationChannel(overlayChannel)
        }
    }
}

