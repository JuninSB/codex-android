package local.codex.mobile.core

import org.json.JSONObject

internal object DeviceControlService {
    val connected = false
    suspend fun execute(command: String): JSONObject = error("Controle do dispositivo indisponível na edição padrão.")
}
