package local.codex.mobile.core

import org.json.JSONObject

internal object DeviceControlService {
    val connected = false
    fun setOverlay(active: Boolean, message: String? = null) = Unit
    suspend fun execute(command: String): JSONObject = error("Controle do dispositivo indisponível na edição padrão.")
}
