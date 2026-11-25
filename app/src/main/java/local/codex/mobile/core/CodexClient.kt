package local.codex.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.IOException

internal fun JSONObject.string(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

internal fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> if (value != null) put(key, value) }
}

data class Attachment(val id: String, val name: String, val image: Boolean) {
    fun data() = obj("id" to id, "name" to name, "image" to image)
}

internal class CodexHttpException(val status: Int, message: String) : IllegalStateException(message)

class CodexClient(private val origin: String) {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun url(route: String, query: Map<String, String> = emptyMap()) =
        (origin + "api/" + route).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

    fun ready(): Boolean = runCatching {
        http.newCall(Request.Builder().url(url("health")).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private suspend fun body(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { result ->
            val data = result.body?.string().orEmpty()
            if (!result.isSuccessful) {
                val error = runCatching { JSONObject(data).string("error") }.getOrNull()
                throw CodexHttpException(result.code, error ?: "O serviço retornou HTTP ${result.code}.")
            }
            data
        }
    }

    private suspend fun getBody(route: String, query: Map<String, String> = emptyMap()): String {
        val request = Request.Builder().url(url(route, query)).build()
        return try { body(request) } catch (_: IOException) {
            body(request.newBuilder().header("Connection", "close").build())
        }
    }

    suspend fun get(route: String, query: Map<String, String> = emptyMap()): JSONObject {
        val response = getBody(route, query)
        return withContext(Dispatchers.Default) { JSONObject(response) }
    }

    fun mediaUrl(path: String): String {
        val base = origin.toHttpUrl()
        val secret = base.pathSegments.first()
        val expires = System.currentTimeMillis() / 1000 + 28_800
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val ticket = mac.doFinal("video\n$expires\n$path".toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        return base.newBuilder().setPathSegment(0, ticket).addPathSegments("api/media")
            .addQueryParameter("path", path).addQueryParameter("expires", expires.toString()).build().toString()
    }

    suspend fun post(route: String, data: JSONObject = obj()): JSONObject =
        JSONObject(body(Request.Builder().url(url(route))
            .post(data.toString().toRequestBody("application/json".toMediaType())).build()))

    suspend fun models() =
        JSONArray(getBody("models")).objects().filterNot { it.optBoolean("hidden") }

}
