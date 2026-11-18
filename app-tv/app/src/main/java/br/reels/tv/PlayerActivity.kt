package br.reels.tv

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Toca a fila em tela cheia.
 * ◀ ▶ = anterior/próximo · OK = pausar · Voltar = sair.
 * Cada vídeo que termina (ou que você pula) é marcado como visto.
 */
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var overlay: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private val queue = mutableListOf<String>()   // ordem desejada (shortcodes)
    private val loaded = mutableListOf<String>()  // já adicionados ao player
    private var current: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        overlay = findViewById(R.id.overlay)

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.player).apply {
            useController = false
            this.player = this@PlayerActivity.player
        }

        val mode = intent.getStringExtra("mode") ?: "new"
        val start = intent.getStringExtra("start")
        val all = Repo.items.value
        val base = if (mode == "all") all else all.filter { !it.item.watched || it.item.shortcode == start }
        val startIdx = base.indexOfFirst { it.item.shortcode == start }.coerceAtLeast(0)
        queue += base.drop(startIdx).map { it.item.shortcode }

        appendReady()
        if (loaded.isEmpty()) {
            Toast.makeText(this, "Nenhum vídeo baixado ainda.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current?.let { Repo.markWatched(it) }
                current = mediaItem?.mediaId
                appendReady()
                showInfo()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    current?.let { Repo.markWatched(it) }
                    appendReady()
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.play()
                    } else {
                        Toast.makeText(this@PlayerActivity, "Fim da playlist 🎉", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else finish()
            }
        })

        current = loaded.first()
        player.prepare()
        player.playWhenReady = true
        showInfo("◀ ▶ pular   ·   OK pausar   ·   Voltar sair")
    }

    /** Adiciona ao player os vídeos da fila que já terminaram de baixar. */
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

    private fun showInfo(extra: String? = null) {
        val idx = player.currentMediaItemIndex + 1
        val total = queue.size
        val title = Repo.items.value.firstOrNull { it.item.shortcode == current }?.item?.title ?: ""
        overlay.text = listOfNotNull("$idx / $total   $title".trim(), extra).joinToString("\n")
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                appendReady()
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                else Toast.makeText(this, "Este é o último baixado", Toast.LENGTH_SHORT).show()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0)
                else player.seekToPreviousMediaItem()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (player.isPlaying) { player.pause(); showInfo("⏸ pausado") } else { player.play(); showInfo() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                showInfo("◀ ▶ pular   ·   OK pausar   ·   Voltar sair")
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
