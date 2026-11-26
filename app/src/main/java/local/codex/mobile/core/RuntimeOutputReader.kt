package local.codex.mobile.core

import java.io.IOException
import java.io.InputStream

internal fun drainRuntimeOutput(input: InputStream, alive: () -> Boolean, record: (String) -> Unit) {
    try {
        input.bufferedReader().useLines { lines -> lines.forEach(record) }
    } catch (_: IOException) {
        if (alive()) record("Leitura da saída do Ubuntu interrompida.")
    }
}
