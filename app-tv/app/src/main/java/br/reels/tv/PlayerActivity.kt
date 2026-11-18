package br.reels.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Player no estilo YouTube Shorts.
 *
 * TV:      ▲ ▼ = vídeo anterior / próximo · OK = pausar · ◀ = voltar 5 s · ▶ = ir para as ações
 *          (nas ações: ▲ ▼ escolhe, OK aciona, ◀ ou Voltar retorna ao vídeo)
 * Celular: deslize para cima / baixo · toque = pausar · toque duplo = favoritar
 *
 * Cada vídeo que termina (ou que você pula) é marcado como visto.
 */
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var videoCard: FrameLayout
    private lateinit var actions: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var bigIcon: ImageView
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val queue = mutableListOf<String>()   // ordem desejada (shortcodes)
    private val loaded = mutableListOf<String>()  // já adicionados ao player (mesma ordem do player)
    private var current: String? = null
    private val keepUnwatched = mutableSetOf<String>() // o usuário desmarcou "visto" — não remarcar ao sair

    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f)
    private var speedIdx = 0

    private class Action(val root: View, val circle: View, val icon: ImageView, val label: TextView)
    private lateinit var aFav: Action
    private lateinit var aSeen: Action
    private lateinit var aRepeat: Action
    private lateinit var aSpeed: Action
    private lateinit var aRemove: Action

    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val isTouch get() = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        videoCard = findViewById(R.id.videoCard)
        actions = findViewById(R.id.actions)
        progress = findViewById(R.id.progress)
        bigIcon = findViewById(R.id.bigIcon)
        hint = findViewById(R.id.hint)
        videoCard.clipToOutline = true

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.player).player = player

        queue += intent.getStringArrayExtra("queue")?.toList().orEmpty()
        // Ao girar a tela, continua do mesmo vídeo
        val resumeSc = savedInstanceState?.getString("current")
        val resumePos = savedInstanceState?.getLong("pos") ?: 0L
        if (resumeSc != null && resumeSc in queue) {
            val idx = queue.indexOf(resumeSc)
            repeat(idx) { queue.removeAt(0) }
        }

        appendReady()
        if (loaded.isEmpty()) {
            Toast.makeText(this, "Nenhum vídeo baixado ainda.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildActions()
        player.addListener(listener)
        current = loaded.first()
        player.prepare()
        if (resumePos > 0) player.seekTo(resumePos)
        player.playWhenReady = true
        refreshInfo()
        setupGestures()
        videoCard.requestFocus()
        videoCard.post { sizeVideoCard(null) }
        showHint(
            if (isTouch) "Deslize para cima para o próximo · toque duplo = ♥"
            else "▲ ▼ trocar vídeo   ·   OK pausar   ·   ▶ ações"
        )
        handler.post(progressTick)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current", current)
        if (::player.isInitialized) outState.putLong("pos", player.currentPosition)
    }

    // ---------- Fila ----------

    /** Adiciona ao player os vídeos da fila que já terminaram de baixar, mantendo a ordem. */
    private fun appendReady() {
        val byCode = Repo.items.value.associateBy { it.item.shortcode }
        for (sc in queue) {
            if (sc in loaded) continue
            val f = byCode[sc]?.file ?: continue
            if (!f.exists()) continue
            player.addMediaItem(MediaItem.Builder().setUri(Uri.fromFile(f)).setMediaId(sc).build())
            loaded += sc
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            current?.let { if (it !in keepUnwatched) Repo.markWatched(it) }
            current = mediaItem?.mediaId
            appendReady()
            refreshInfo()
            showInfoPulse()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                current?.let { if (it !in keepUnwatched) Repo.markWatched(it) }
                appendReady()
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                } else {
                    Toast.makeText(this@PlayerActivity, "Fim da fila 🎉", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = sizeVideoCard(videoSize)

        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshInfo()

        override fun onPlayerError(error: PlaybackException) {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else finish()
        }
    }

    private fun next() {
        appendReady()
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        else Toast.makeText(this, "Este é o último vídeo baixado", Toast.LENGTH_SHORT).show()
    }

    private fun previous() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        flashIcon(if (player.isPlaying) R.drawable.ic_play else R.drawable.ic_pause)
    }

    // ---------- Layout do vídeo ----------

    /** Na horizontal, o card do vídeo ocupa a altura toda e a largura segue a proporção do vídeo. */
    private fun sizeVideoCard(size: VideoSize?) {
        if (!isLandscape) return
        val stage = findViewById<View>(R.id.stage) ?: return
        val h = stage.height - stage.paddingTop - stage.paddingBottom
        if (h <= 0) return
        val ratio = if (size != null && size.width > 0 && size.height > 0)
            size.width * size.pixelWidthHeightRatio / size.height else 9f / 16f
        val maxW = stage.width - dp(96) * 2 - dp(32)
        val lp = videoCard.layoutParams
        lp.width = (h * ratio).toInt().coerceIn(dp(200), maxW.coerceAtLeast(dp(200)))
        videoCard.layoutParams = lp
    }

    // ---------- Ações laterais ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun addAction(iconRes: Int, text: String, onClick: () -> Unit): Action {
        val size = resources.getDimensionPixelSize(R.dimen.action_size)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onClick() }
        }
        // O círculo herda os estados do item (focado / ativo), e o seletor action_circle muda a cor
        val circle = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.action_circle)
            isDuplicateParentStateEnabled = true
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        circle.addView(icon, FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER))
        root.addView(circle, LinearLayout.LayoutParams(size, size))
        val label = TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
        }
        root.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        // Foco do controle remoto: círculo branco com ícone escuro
        root.setOnFocusChangeListener { _, has ->
            icon.imageTintList = ColorStateList.valueOf(if (has) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
            root.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(120).start()
        }
        actions.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        return Action(root, circle, icon, label)
    }

    private fun setActive(a: Action, active: Boolean) {
        a.root.isActivated = active
    }

    private fun buildActions() {
        aFav = addAction(R.drawable.ic_heart, "Favorito") { toggleFavorite() }
        aSeen = addAction(R.drawable.ic_check, "Visto") { toggleSeen() }
        aRepeat = addAction(R.drawable.ic_repeat, "Repetir") {
            player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            refreshInfo()
            toast(if (player.repeatMode == Player.REPEAT_MODE_ONE) "Repetindo este vídeo" else "Repetição desligada")
        }
        aSpeed = addAction(R.drawable.ic_play, "1x") {
            speedIdx = (speedIdx + 1) % speeds.size
            player.setPlaybackSpeed(speeds[speedIdx])
            refreshInfo()
        }
        // "Abrir no Instagram" só aparece se o app do Instagram estiver instalado (celular)
        if (instagramInstalled()) {
            addAction(R.drawable.ic_open, "Instagram") {
                val sc = current ?: return@addAction
                player.pause()
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/reel/$sc/"))
                            .setPackage("com.instagram.android")
                    )
                }
            }
        }
        aRemove = addAction(R.drawable.ic_delete, "Remover") { confirmRemove() }
        // Nas ações, ▲ no primeiro / ▼ no último não sai da coluna
        actions.getChildAt(0)?.nextFocusUpId = View.NO_ID
    }

    private fun instagramInstalled() = try {
        packageManager.getPackageInfo("com.instagram.android", 0); true
    } catch (_: Exception) {
        false
    }

    private fun toggleFavorite() {
        val sc = current ?: return
        val fav = Repo.toggleFavorite(sc)
        refreshInfo()
        flashIcon(R.drawable.ic_heart)
        toast(if (fav) "Adicionado aos favoritos" else "Removido dos favoritos")
    }

    private fun toggleSeen() {
        val sc = current ?: return
        val watched = Repo.item(sc)?.watched == true
        if (watched) {
            keepUnwatched += sc
            Repo.markWatched(sc, false)
            toast("Marcado como não visto")
        } else {
            keepUnwatched -= sc
            Repo.markWatched(sc, true)
            toast("Marcado como visto")
        }
        refreshInfo()
    }

    private fun confirmRemove() {
        val sc = current ?: return
        player.pause()
        AlertDialog.Builder(this)
            .setMessage("Remover este Reel da playlist? Ele some de todos os aparelhos.")
            .setPositiveButton("Remover") { _, _ ->
                val idx = player.currentMediaItemIndex
                keepUnwatched += sc // não precisa marcar como visto
                Repo.remove(sc)
                queue.remove(sc)
                loaded.remove(sc)
                current = null
                if (player.mediaItemCount <= 1) {
                    toast("Removido. Fim da fila.")
                    finish()
                } else {
                    player.removeMediaItem(idx)
                    player.play()
                    toast("Removido da playlist")
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> player.play() }
            .setOnCancelListener { player.play() }
            .show()
    }

    // ---------- Informações na tela ----------

    private fun refreshInfo() {
        if (!::aFav.isInitialized) return
        val it = Repo.item(current)
        val idx = (queue.indexOf(current) + 1).coerceAtLeast(1)
        findViewById<TextView>(R.id.counter).text = "$idx / ${queue.size}"
        findViewById<TextView>(R.id.title).text = it?.title ?: "Reel"
        findViewById<TextView>(R.id.sub).text = buildList {
            if (it?.favorite == true) add("♥ favorito")
            if (it?.watched == true) add("✓ visto")
            if (player.repeatMode == Player.REPEAT_MODE_ONE) add("repetindo")
            if (speeds[speedIdx] != 1f) add("${speeds[speedIdx]}x")
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) add("pausado")
        }.joinToString("  ·  ").ifEmpty { "instagram.com/reel/${current ?: ""}" }

        setActive(aFav, it?.favorite == true)
        setActive(aSeen, it?.watched == true)
        setActive(aRepeat, player.repeatMode == Player.REPEAT_MODE_ONE)
        val s = speeds[speedIdx]
        aSpeed.label.text = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"
        setActive(aSpeed, s != 1f)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val d = player.duration
            progress.progress = if (d > 0) (player.currentPosition * 1000 / d).toInt() else 0
            handler.postDelayed(this, 250)
        }
    }

    private fun flashIcon(res: Int) {
        bigIcon.setImageResource(res)
        bigIcon.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        bigIcon.alpha = 1f
        bigIcon.scaleX = 0.8f; bigIcon.scaleY = 0.8f
        bigIcon.visibility = View.VISIBLE
        bigIcon.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(350).setDuration(350)
            .withEndAction { bigIcon.visibility = View.GONE }.start()
    }

    private fun showInfoPulse() {
        val info = findViewById<View>(R.id.info)
        info.alpha = 0.4f
        info.animate().alpha(1f).setDuration(300).start()
    }

    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).withEndAction { hint.visibility = View.GONE }.start() }
    private fun showHint(text: String) {
        hint.text = text
        hint.alpha = 1f
        hint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, 4500)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------- Controle remoto ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val inActions = actions.hasFocus()
        when (event.keyCode) {
            // Teclas de mídia funcionam sempre
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_DOWN -> { next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP -> { previous(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekTo(player.currentPosition + 10_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MENU -> { toggleFavorite(); return true }
        }
        if (inActions) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK -> { videoCard.requestFocus(); return true }
            }
            return super.dispatchKeyEvent(event) // ▲ ▼ OK navegam entre as ações
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { next(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { previous(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { actions.getChildAt(0)?.requestFocus(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                player.seekTo((player.currentPosition - 5_000).coerceAtLeast(0))
                showHint("⏪ 5 segundos")
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Gestos (celular) ----------

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { togglePlay(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val sc = current ?: return true
                if (Repo.item(sc)?.favorite != true) toggleFavorite() else flashIcon(R.drawable.ic_heart)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(vy) > abs(vx) && abs(dy) > dp(60)) {
                    if (dy < 0) next() else previous()
                    return true
                }
                return false
            }
        })
        videoCard.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}
