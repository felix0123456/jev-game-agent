package com.jev.gameagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.jev.gameagent.core.AgentCoordinator
import com.jev.gameagent.jev.JevApiClient

class JevApplication : Application() {

    companion object {
        const val CHANNEL_ID_MEDIA_PROJECTION = "jev_media_projection_channel"
        const val CHANNEL_ID_OVERLAY = "jev_overlay_channel"
        lateinit var instance: JevApplication
            private set
    }

    lateinit var jevApiClient: JevApiClient
    lateinit var coordinator: AgentCoordinator

    override fun onCreate() {
        super.onCreate()
        instance = this

        jevApiClient = JevApiClient(this)
        coordinator = AgentCoordinator(this, jevApiClient)

        createNotificationChannels()
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
