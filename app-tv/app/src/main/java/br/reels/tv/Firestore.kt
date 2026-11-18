package br.reels.tv

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Item(
    val shortcode: String,
    val url: String,
    val addedAt: String,
    val watched: Boolean,
    val title: String?,
)

/** Cliente mínimo da API REST do Firestore (sem SDK, sem google-services.json). */
class Firestore(projectId: String, private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val root = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"
    private val jsonType = "application/json".toMediaType()

    /** Executa a requisição. Retorna null para 404. */
    private fun call(req: Request): String? {
        http.newCall(req).execute().use { res ->
            if (res.code == 404) return null
            if (!res.isSuccessful) throw RuntimeException("Firestore HTTP ${res.code}")
            return res.body?.string() ?: ""
        }
    }

    private fun JSONObject.str(k: String): String? =
        optJSONObject(k)?.let { it.optString("stringValue", it.optString("timestampValue", "")) }
            ?.takeIf { it.isNotEmpty() }

    private fun JSONObject.bool(k: String): Boolean =
        optJSONObject(k)?.optBoolean("booleanValue", false) ?: false

    fun listItems(code: String): List<Item> {
        val out = mutableListOf<Item>()
        var pageToken: String? = null
        do {
            var url = "$root/playlists/$code/items?pageSize=300&key=$apiKey"
            if (pageToken != null) url += "&pageToken=$pageToken"
            val body = call(Request.Builder().url(url).build()) ?: return out
            val obj = JSONObject(body)
            val docs = obj.optJSONArray("documents")
            if (docs != null) {
                for (i in 0 until docs.length()) {
                    val f = docs.getJSONObject(i).optJSONObject("fields") ?: continue
                    val sc = f.str("shortcode") ?: continue
                    out += Item(
                        shortcode = sc,
                        url = f.str("url") ?: "https://www.instagram.com/reel/$sc/",
                        addedAt = f.str("addedAt") ?: "",
                        watched = f.bool("watched"),
                        title = f.str("title"),
                    )
                }
            }
            pageToken = obj.optString("nextPageToken", "").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    fun setWatched(code: String, shortcode: String, watched: Boolean) =
        patch(code, shortcode, "watched", JSONObject().put("booleanValue", watched))

    fun setTitle(code: String, shortcode: String, title: String) =
        patch(code, shortcode, "title", JSONObject().put("stringValue", title))

    private fun patch(code: String, shortcode: String, field: String, value: JSONObject) {
        val url = "$root/playlists/$code/items/$shortcode?key=$apiKey" +
            "&updateMask.fieldPaths=$field&currentDocument.exists=true"
        val body = JSONObject().put("fields", JSONObject().put(field, value)).toString()
        call(Request.Builder().url(url).patch(body.toRequestBody(jsonType)).build())
    }

    fun deleteItem(code: String, shortcode: String) {
        call(Request.Builder().url("$root/playlists/$code/items/$shortcode?key=$apiKey").delete().build())
    }

    /** Procura o pedido de pareamento criado pela extensão. Retorna o código da playlist ou null. */
    fun getPairing(pin: String): String? {
        val body = call(Request.Builder().url("$root/pairing/$pin?key=$apiKey").build()) ?: return null
        return JSONObject(body).optJSONObject("fields")?.str("playlistCode")
    }

    fun deletePairing(pin: String) {
        runCatching { call(Request.Builder().url("$root/pairing/$pin?key=$apiKey").delete().build()) }
    }
}
