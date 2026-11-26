package local.codex.mobile.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import local.codex.mobile.MainActivity
import local.codex.mobile.R

internal object DeviceControlSession {
    @Volatile var chat: String? = null
        private set
    fun start(context: Context, chatId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        check(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) { "Ative as notificações para liberar o controle do dispositivo." }
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("device-control", "Controle do dispositivo", NotificationManager.IMPORTANCE_LOW))
        check(manager.getNotificationChannel("device-control").importance != NotificationManager.IMPORTANCE_NONE) { "Ative as notificações de controle do dispositivo." }
        val stop = PendingIntent.getBroadcast(context, 0, Intent(context, StopDeviceControlReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "device-control")
            .setSmallIcon(R.drawable.symbol_terminal).setContentTitle("Codex controlando o dispositivo")
            .setContentText("Controle autorizado para uma conversa.").setOngoing(true)
            .setContentIntent(open).addAction(0, "Encerrar controle", stop).build()
        manager.notify(41740, notification)
        chat = chatId
    }
    fun stop(context: Context) {
        chat = null
        context.getSystemService(NotificationManager::class.java).cancel(41740)
    }
}

class StopDeviceControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { DeviceControlSession.stop(context) }
}
