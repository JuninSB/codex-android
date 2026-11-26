package local.codex.mobile.core

import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeOutputReaderTest {
    private fun interrupted() = object : InputStream() {
        override fun read(): Int = throw InterruptedIOException("closed")
    }

    @Test fun closingStreamAfterProcessEndsDoesNotCrash() {
        val output = mutableListOf<String>()
        drainRuntimeOutput(interrupted(), { false }, { output.add(it) })
        assertEquals(emptyList<String>(), output)
    }

    @Test fun unexpectedReadFailureIsReportedWithoutExceptionContents() {
        val output = mutableListOf<String>()
        drainRuntimeOutput(interrupted(), { true }, { output.add(it) })
        assertEquals(listOf("Leitura da saída do Ubuntu interrompida."), output)
    }
}
