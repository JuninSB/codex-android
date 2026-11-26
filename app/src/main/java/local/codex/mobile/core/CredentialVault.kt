package local.codex.mobile.core

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class CredentialType(val title: String) { TOKEN("Token"), LOGIN("Login"), SSH("SSH") }

internal data class CredentialLabel(
    val name: String, val description: String, val type: CredentialType, val instructions: String = ""
) {
    fun metadata() = obj("name" to name, "description" to description, "type" to type.name,
        "instructions" to instructions.ifBlank {
            when (type) {
                CredentialType.TOKEN -> "Use credential_shell com a URL HTTPS aprovada ou file:caminho para gravar o token solicitado dentro do projeto."
                CredentialType.LOGIN -> "Use credential_shell com a URL HTTPS aprovada. O app envia a autenticação Basic."
                CredentialType.SSH -> "Entrada SSH. O app guarda os dados, e a conexão SSH ainda não está disponível nesta interface."
            }
        })
}

internal class Credential(
    val label: CredentialLabel,
    private val secret: String,
    val username: String = "",
    val host: String = "",
    val port: Int = 22
) {
    fun json() = obj("name" to label.name, "description" to label.description, "type" to label.type.name,
        "instructions" to label.instructions, "secret" to secret, "username" to username, "host" to host, "port" to port)

    fun metadata(): JSONObject = label.metadata().apply {
        for (field in listOf("description", "instructions")) {
            put(field, getString(field).replace(secret, "[oculto]"))
        }
    }

    fun requestJson() = obj("name" to label.name, "type" to label.type.name, "secret" to secret,
        "username" to username.takeIf { label.type == CredentialType.LOGIN })

    companion object {
        fun parse(data: JSONObject): Credential = Credential(
            CredentialLabel(data.getString("name"), data.getString("description"),
                CredentialType.valueOf(data.getString("type")), data.optString("instructions")),
            data.getString("secret"), data.getString("username"), data.getString("host"), data.getInt("port")
        )
    }
}

internal object VaultEncryption {
    private val random = SecureRandom()
    private val aad = "Codex Mobile credentials v1".toByteArray()
    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    fun key(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }

    fun encrypt(key: SecretKey, nonce: ByteArray, text: ByteArray): ByteArray =
        crypt(Cipher.ENCRYPT_MODE, key, nonce, text)

    fun decrypt(key: SecretKey, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        crypt(Cipher.DECRYPT_MODE, key, nonce, ciphertext)

    private fun crypt(mode: Int, key: SecretKey, nonce: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }
}

internal class CredentialVault(context: Context) {
    class Session(val key: SecretKey, val salt: ByteArray, val entries: List<Credential>)
    private val storage = AtomicFile(File(context.filesDir, "credentials-v1.enc"))
    private val b64 = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun exists() = storage.baseFile.exists()

    suspend fun open(password: String, create: Boolean = false): Session = withContext(Dispatchers.IO) {
        if (create) {
            check(!exists()) { "O cofre já existe." }
            require(password.length >= 8) { "Use uma senha de pelo menos 8 caracteres." }
            val salt = VaultEncryption.randomBytes(16)
            val session = Session(VaultEncryption.key(password.toCharArray(), salt), salt, emptyList())
            save(session, emptyList())
            session
        } else {
            check(exists()) { "Abra Credenciais e crie o cofre primeiro." }
            val record = JSONObject(storage.readFully().toString(Charsets.UTF_8))
            check(record.getInt("version") == 1) { "Versão do cofre incompatível." }
            val salt = decoder.decode(record.getString("salt"))
            val nonce = decoder.decode(record.getString("nonce"))
            val key = VaultEncryption.key(password.toCharArray(), salt)
            val plaintext = try {
                VaultEncryption.decrypt(key, nonce, decoder.decode(record.getString("ciphertext")))
            } catch (_: AEADBadTagException) {
                error("Senha incorreta ou cofre danificado.")
            }
            try {
                val entries = JSONArray(plaintext.toString(Charsets.UTF_8)).objects().map(Credential::parse)
                Session(key, salt, entries)
            } finally { plaintext.fill(0) }
        }
    }

    suspend fun save(session: Session, entries: List<Credential>): Session = withContext(Dispatchers.IO) {
        val nonce = VaultEncryption.randomBytes(12)
        val plaintext = JSONArray(entries.map(Credential::json)).toString().toByteArray()
        val encrypted = try { VaultEncryption.encrypt(session.key, nonce, plaintext) } finally { plaintext.fill(0) }
        val data = obj("version" to 1, "salt" to b64.encodeToString(session.salt),
            "nonce" to b64.encodeToString(nonce), "ciphertext" to b64.encodeToString(encrypted)).toString().toByteArray()
        val stream = storage.startWrite()
        try {
            stream.write(data)
            storage.finishWrite(stream)
        } catch (error: Exception) {
            storage.failWrite(stream)
            throw error
        }
        Session(session.key, session.salt, entries)
    }
}
