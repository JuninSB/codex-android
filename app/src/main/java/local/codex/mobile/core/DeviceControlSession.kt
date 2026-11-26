package local.codex.mobile.core

import android.content.Context

internal object DeviceControlSession {
    @Volatile var chat: String? = null
        private set
    fun start(context: Context, chatId: String) {
        chat = chatId
    }
    fun stop(context: Context) {
        chat = null
        DeviceControlService.setOverlay(false)
    }
}
