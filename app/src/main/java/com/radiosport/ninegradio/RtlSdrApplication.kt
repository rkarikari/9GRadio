package com.radiosport.ninegradio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.radiosport.ninegradio.data.AppDatabase

class RtlSdrApplication : Application() {

    companion object {
        const val CHANNEL_SDR_SERVICE = "rtlsdr_service"
        const val CHANNEL_RECORDING = "rtlsdr_recording"
        const val CHANNEL_SCANNER = "rtlsdr_scanner"

        lateinit var instance: RtlSdrApplication
            private set
    }

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val sdrChannel = NotificationChannel(
            CHANNEL_SDR_SERVICE,
            "SDR Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "9GRadio background processing service"
            setShowBadge(false)
        }

        val recChannel = NotificationChannel(
            CHANNEL_RECORDING,
            "IQ Recording",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "IQ and audio recording notifications"
        }

        val scanChannel = NotificationChannel(
            CHANNEL_SCANNER,
            "Frequency Scanner",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Frequency scanner status"
        }

        manager.createNotificationChannels(listOf(sdrChannel, recChannel, scanChannel))
    }
}
