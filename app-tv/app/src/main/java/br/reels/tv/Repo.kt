package br.reels.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

enum class St { QUEUED, DOWNLOADING, READY, ERROR }

data class UiItem(val item: Item, val state: St, val file: File?, val thumb: File?, val error: String?)

/** Abas / modos de reprodução, usados na tela inicial e no player. */
enum class Mode(val key: String, val label: String, val playLabel: String) {
    NEW("new", "Novos", "Assistir novos"),
    ALL("all", "Todos", "Assistir tudo"),
    FAV("fav", "Favoritos", "Assistir favoritos"),
    SEEN("seen", "Vistos", "Rever vistos");

    fun matches(i: Item) = when (this) {
        NEW -> !i.watched
        ALL -> true
        FAV -> i.favorite
        SEEN -> i.watched
    }

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: NEW
    }
}

/**
 * Estado central do app: lista vinda do Firestore + downloads locais feitos com yt-dlp.
 * Os vídeos são baixados no próprio aparelho, pela internet de casa. Nada passa por servidor.
 */
object Repo {
    private const val MAX_AHEAD = 20   // quantos vídeos não assistidos manter baixados
    private const val MAX_FILES = 40   // limite total de vídeos guardados (protege a memória do Fire Stick)

    private lateinit var app: Context
    private lateinit var fs: Firestore
    private lateinit var videoDir: File

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<UiItem>>(emptyList())
    val items: StateFlow<List<UiItem>> = _items
    val status = MutableStateFlow("Iniciando…")

    @Volatile private var remote: List<Item> = emptyList()
    private val errors = ConcurrentHashMap<String, String>()
    @Volatile private var downloading: String? = null
    private var worker: Job? = null
    private val ytReady = CompletableDeferred<Boolean>()

    // Capas: quais já procuramos no Firebase nesta sessão e quais já existem lá
    private val thumbTried = ConcurrentHashMap.newKeySet<String>()
    private val thumbRemote = ConcurrentHashMap.newKeySet<String>()
    private var thumbJob: Job? = null

    val configured get() = BuildConfig.FIREBASE_PROJECT_ID.isNotEmpty() && BuildConfig.FIREBASE_API_KEY.isNotEmpty()
    private val prefs get() = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val playlistCode: String? get() = prefs.getString("code", null)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        fs = Firestore(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_API_KEY)
        videoDir = File(app.filesDir, "videos").apply { mkdirs() }
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                status.value = "Atualizando o yt-dlp…"
                // O Instagram muda com frequência; manter o yt-dlp atualizado evita quebras.
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE) }
                status.value = ""
                ytReady.complete(true)
            } catch (t: Throwable) {
                status.value = "Erro ao iniciar o yt-dlp: ${t.message}"
                ytReady.complete(false)
            }
        }
    }

    // ---------- Pareamento ----------

    fun newPin(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    /** Fica esperando a extensão criar o documento pairing/{pin}. */
    suspend fun waitPairing(pin: String) = withContext(Dispatchers.IO) {
        while (isActive) {
            val code = runCatching { fs.getPairing(pin) }.getOrNull()
            if (code != null && code.length >= 24) {
                prefs.edit().putString("code", code).apply()
                fs.deletePairing(pin)
                return@withContext
            }
            delay(3000)
        }
    }

    /** Sai da playlist. Se [removeDevice], também tira esta TV da lista de aparelhos. */
    fun disconnect(removeDevice: Boolean = true) {
        val code = playlistCode
        if (removeDevice && code != null) scope.launch { fs.deleteDevice(code, deviceId) }
        prefs.edit().remove("code").remove("lastBeat").apply()
        remote = emptyList()
        thumbTried.clear()
        thumbRemote.clear()
        videoDir.listFiles()?.forEach { it.delete() }
        rebuild()
    }

    // ---------- Aparelhos ----------

    /** Incrementa quando esta TV é removida por outro aparelho (a tela volta ao pareamento). */
    val revokedEvent = MutableStateFlow(0)

    private val deviceId: String
        get() = prefs.getString("deviceId", null) ?: run {
            val chars = "abcdefghijkmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            val id = (1..20).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            prefs.edit().putString("deviceId", id).apply()
            id
        }

    private val deviceName: String
        get() {
            val maker = android.os.Build.MANUFACTURER ?: ""
            if (maker.equals("Amazon", ignoreCase = true)) return "Fire TV"
            val model = android.os.Build.MODEL ?: "TV"
            val full = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            return full.trim().replaceFirstChar { it.uppercase() }.take(60)
        }

    /** Retorna false se esta TV foi removida. Faz no máximo uma chamada a cada 10 min. */
    private fun heartbeat(code: String, force: Boolean): Boolean {
        val last = prefs.getLong("lastBeat", 0L)
        if (!force && System.currentTimeMillis() - last < 10 * 60_000) return true
        return try {
            val ok = fs.heartbeat(code, deviceId, deviceName)
            if (ok) prefs.edit().putLong("lastBeat", System.currentTimeMillis()).apply()
            ok
        } catch (_: Throwable) {
            true // sem rede: tenta de novo depois
        }
    }

    // ---------- Sincronização ----------

    suspend fun refresh(retryErrors: Boolean = false) = withContext(Dispatchers.IO) {
        val code = playlistCode ?: return@withContext
        if (!heartbeat(code, force = retryErrors)) {
            disconnect(removeDevice = false)
            revokedEvent.value = revokedEvent.value + 1
            return@withContext
        }
        if (retryErrors) errors.clear()
        try {
            remote = fs.listItems(code).sortedBy { it.addedAt }
            if (status.value.startsWith("Sem conexão")) status.value = ""
        } catch (t: Throwable) {
            status.value = "Sem conexão com o Firebase (${t.message})"
        }
        cleanup()
        rebuild()
        ensureWorker()
        fetchRemoteThumbs(code)
    }

    /**
     * Baixa do Firebase as capas que esta TV ainda não tem (geradas pela extensão ou por outra TV).
     * Assim os cards já aparecem com imagem antes mesmo de o vídeo ser baixado.
     */
    private fun fetchRemoteThumbs(code: String) {
        if (thumbJob?.isActive == true) return
        thumbJob = scope.launch {
            var changed = 0
            for (it in remote.toList()) {
                val sc = it.shortcode
                if (thumbFile(sc).exists() || !thumbTried.add(sc)) continue
                val b64 = runCatching { fs.getThumb(code, sc) }.getOrNull() ?: continue
                thumbRemote.add(sc)
                runCatching { thumbFile(sc).writeBytes(Base64.decode(b64, Base64.DEFAULT)) }
                if (++changed % 6 == 0) rebuild()
            }
            if (changed > 0) rebuild()
        }
    }

    /** Envia a capa gerada aqui para o Firebase (se ainda não existir lá). */
    private fun uploadThumb(sc: String) {
        val code = playlistCode ?: return
        if (thumbRemote.contains(sc)) return
        val local = thumbFile(sc).takeIf { it.exists() } ?: return
        try {
            if (!thumbTried.contains(sc) && fs.getThumb(code, sc) != null) {
                thumbRemote.add(sc); thumbTried.add(sc); return
            }
            val bmp = BitmapFactory.decodeFile(local.absolutePath) ?: return
            val w = 240
            val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                Bitmap.createScaledBitmap(bmp, w, h, true).compress(Bitmap.CompressFormat.JPEG, 70, out)
                out.toByteArray()
            }
            fs.putThumb(code, sc, Base64.encodeToString(bytes, Base64.NO_WRAP))
            thumbRemote.add(sc); thumbTried.add(sc)
        } catch (_: Throwable) {
        }
    }

    private fun videoFile(sc: String) = File(videoDir, "$sc.mp4")
    private fun thumbFile(sc: String) = File(videoDir, "$sc.jpg")

    private fun rebuild() {
        _items.value = remote.map { it ->
            val f = videoFile(it.shortcode).takeIf { f -> f.exists() }
            val state = when {
                f != null -> St.READY
                downloading == it.shortcode -> St.DOWNLOADING
                errors.containsKey(it.shortcode) -> St.ERROR
                else -> St.QUEUED
            }
            UiItem(it, state, f, thumbFile(it.shortcode).takeIf { t -> t.exists() }, errors[it.shortcode])
        }
    }

    private fun cleanup() {
        if (remote.isEmpty() && playlistCode == null) return
        val known = remote.map { it.shortcode }.toSet()
        // Apaga arquivos de itens removidos da playlist
        videoDir.listFiles()?.forEach { f ->
            if (f.nameWithoutExtension !in known) f.delete()
        }
        // Se passou do limite, apaga os vídeos já assistidos mais antigos
        // (favoritos nunca são apagados; as capas ficam, porque são pequenas)
        var count = videoDir.listFiles { f -> f.extension == "mp4" }?.size ?: 0
        for (it in remote.filter { r -> r.watched && !r.favorite }) {
            if (count <= MAX_FILES) break
            if (videoFile(it.shortcode).delete()) count--
        }
    }

    // ---------- Downloads ----------

    fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            if (!ytReady.await()) return@launch
            while (isActive) {
                val list = remote
                val ready = list.count { !it.watched && videoFile(it.shortcode).exists() }
                val next = list.firstOrNull {
                    !it.watched && !videoFile(it.shortcode).exists() && !errors.containsKey(it.shortcode)
                }
                if (next == null || ready >= MAX_AHEAD) break
                download(next)
            }
            rebuild()
        }
    }

    /** Baixa um Reel específico agora (usado em "Tentar de novo" e para itens já vistos). */
    fun downloadNow(sc: String) {
        errors.remove(sc)
        val item = remote.firstOrNull { it.shortcode == sc } ?: return
        scope.launch {
            if (!ytReady.await()) return@launch
            while (downloading != null) delay(500)
            if (!videoFile(sc).exists()) download(item)
        }
    }

    private fun download(item: Item) {
        val sc = item.shortcode
        downloading = sc
        rebuild()
        val tmp = File(app.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
        try {
            val req = YoutubeDLRequest(item.url).apply {
                addOption("-o", "${tmp.absolutePath}/$sc.%(ext)s")
                // Prefere MP4 com áudio junto; senão junta vídeo+áudio com ffmpeg
                addOption("-f", "b[ext=mp4]/bv*+ba/b")
                addOption("--merge-output-format", "mp4")
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--no-simulate")
                addOption("--print", "%(uploader)s")
            }
            val resp = YoutubeDL.getInstance().execute(req)
            val file = tmp.listFiles()?.firstOrNull {
                it.name.startsWith(sc) && it.extension.lowercase() in listOf("mp4", "mkv", "webm", "mov")
            } ?: throw RuntimeException("o yt-dlp não gerou nenhum arquivo")
            val dest = videoFile(sc)
            file.copyTo(File(videoDir, "$sc.part"), overwrite = true).renameTo(dest)
            // Gera a capa a partir do vídeo, a menos que já tenha vindo uma (melhor) do Firebase
            if (!thumbFile(sc).exists()) makeThumb(dest, thumbFile(sc))
            uploadThumb(sc)

            val uploader = resp.out.trim().lines().lastOrNull()?.trim()
            if (!uploader.isNullOrBlank() && uploader != "NA" && item.title == null) {
                val title = "@$uploader"
                playlistCode?.let { code -> runCatching { fs.setTitle(code, sc, title) } }
                remote = remote.map { if (it.shortcode == sc) it.copy(title = title) else it }
            }
        } catch (t: Throwable) {
            val msg = (t.message ?: t.javaClass.simpleName).lines().lastOrNull { it.isNotBlank() } ?: "erro"
            errors[sc] = msg.take(160)
        } finally {
            tmp.deleteRecursively()
            downloading = null
            rebuild()
        }
    }

    private fun makeThumb(video: File, out: File) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val frame = r.getFrameAtTime(1_000_000) ?: r.frameAtTime ?: return
            val w = 360
            val h = (frame.height * w.toFloat() / frame.width).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(frame, w, h, true)
            out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (_: Throwable) {
        } finally {
            runCatching { r.release() }
        }
    }

    // ---------- Ações ----------

    fun markWatched(sc: String, watched: Boolean = true) {
        val current = remote.firstOrNull { it.shortcode == sc } ?: return
        if (current.watched == watched) return
        remote = remote.map { if (it.shortcode == sc) it.copy(watched = watched) else it }
        rebuild()
        scope.launch {
            playlistCode?.let { code -> runCatching { fs.setWatched(code, sc, watched) } }
            cleanup()
            ensureWorker()
        }
    }

    fun toggleFavorite(sc: String): Boolean {
        val current = remote.firstOrNull { it.shortcode == sc } ?: return false
        val fav = !current.favorite
        remote = remote.map { if (it.shortcode == sc) it.copy(favorite = fav) else it }
        rebuild()
        scope.launch { playlistCode?.let { code -> runCatching { fs.setFavorite(code, sc, fav) } } }
        return fav
    }

    fun item(sc: String?): Item? = remote.firstOrNull { it.shortcode == sc }

    fun remove(sc: String) {
        remote = remote.filterNot { it.shortcode == sc }
        videoFile(sc).delete()
        thumbFile(sc).delete()
        rebuild()
        scope.launch {
            playlistCode?.let { code ->
                runCatching { fs.deleteItem(code, sc) }
                fs.deleteThumb(code, sc)
            }
        }
    }
}
