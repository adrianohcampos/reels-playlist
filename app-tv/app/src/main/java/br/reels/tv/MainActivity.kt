package br.reels.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val ui = MainScope()
    private var pollJob: Job? = null
    private var pairJob: Job? = null
    private lateinit var adapter: ItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = ItemAdapter(
            onPlay = { ui -> play(if (ui.item.watched) "all" else "new", ui.item.shortcode) },
            onMenu = { ui -> showItemMenu(ui) },
        )
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = this@MainActivity.adapter
            itemAnimator = null
        }
        findViewById<Button>(R.id.btnNew).setOnClickListener { play("new", null) }
        findViewById<Button>(R.id.btnAll).setOnClickListener { play("all", null) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            ui.launch { Repo.refresh(retryErrors = true) }
            Toast.makeText(this, "Atualizando…", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        ui.launch {
            Repo.items.combine(Repo.status) { items, st -> items to st }.collect { (items, st) ->
                adapter.submit(items)
                findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.status).text = statusLine(items, st)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        when {
            !Repo.configured -> {
                showMain()
                findViewById<TextView>(R.id.status).text =
                    "⚠ APK compilado sem FIREBASE_PROJECT_ID / FIREBASE_API_KEY. Veja o LEIA-ME."
            }
            Repo.playlistCode == null -> showPairing()
            else -> showMain()
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pairJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.cancel()
    }

    private fun showPairing() {
        findViewById<View>(R.id.main).visibility = View.GONE
        findViewById<View>(R.id.pairing).visibility = View.VISIBLE
        val pin = Repo.newPin()
        findViewById<TextView>(R.id.pin).text = pin.chunked(4).joinToString("-")
        pairJob?.cancel()
        pairJob = ui.launch {
            Repo.waitPairing(pin)
            Toast.makeText(this@MainActivity, "TV conectada!", Toast.LENGTH_LONG).show()
            showMain()
        }
    }

    private fun showMain() {
        findViewById<View>(R.id.pairing).visibility = View.GONE
        findViewById<View>(R.id.main).visibility = View.VISIBLE
        if (!Repo.configured) return
        pollJob?.cancel()
        pollJob = ui.launch {
            while (isActive) {
                Repo.refresh()
                delay(60_000)
            }
        }
        findViewById<Button>(R.id.btnNew).requestFocus()
    }

    private fun statusLine(items: List<UiItem>, st: String): String {
        val novos = items.count { !it.item.watched }
        val prontos = items.count { !it.item.watched && it.state == St.READY }
        val baixando = items.any { it.state == St.DOWNLOADING }
        val erros = items.count { it.state == St.ERROR }
        val parts = mutableListOf("${items.size} na playlist", "$novos novos", "$prontos prontos para ver")
        if (baixando) parts += "baixando…"
        if (erros > 0) parts += "$erros com erro (☰ no item para opções)"
        if (st.isNotBlank()) parts += st
        return parts.joinToString("  ·  ")
    }

    private fun play(mode: String, start: String?) {
        val items = Repo.items.value
        val hasReady = items.any { it.state == St.READY && (mode == "all" || !it.item.watched || it.item.shortcode == start) }
        if (!hasReady) {
            val target = items.firstOrNull { it.item.shortcode == start }
            if (target != null && target.state != St.DOWNLOADING) Repo.downloadNow(target.item.shortcode)
            Toast.makeText(this, "Ainda não há vídeo baixado para tocar. Aguarde um pouco…", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("mode", mode).putExtra("start", start))
    }

    private fun showItemMenu(ui: UiItem) {
        val sc = ui.item.shortcode
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += (if (ui.item.watched) "Marcar como não visto" else "Marcar como visto") to {
            Repo.markWatched(sc, !ui.item.watched)
        }
        if (ui.state == St.ERROR || ui.state == St.QUEUED) options += "Baixar agora / tentar de novo" to { Repo.downloadNow(sc) }
        options += "Abrir pelo player do Instagram (embed)" to {
            startActivity(Intent(this, EmbedActivity::class.java).putExtra("sc", sc))
        }
        options += "Remover da playlist" to { Repo.remove(sc) }

        val title = buildString {
            append(ui.item.title ?: sc)
            if (ui.error != null) append("\nErro: ").append(ui.error)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Reels TV ${BuildConfig.VERSION_NAME}")
            .setItems(arrayOf("Desconectar esta TV da playlist", "Cancelar")) { _, which ->
                if (which == 0) {
                    AlertDialog.Builder(this)
                        .setMessage("Desconectar? Os vídeos baixados nesta TV serão apagados (a playlist continua salva).")
                        .setPositiveButton("Desconectar") { _, _ -> Repo.disconnect(); showPairing() }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .show()
    }
}

class ItemAdapter(
    private val onPlay: (UiItem) -> Unit,
    private val onMenu: (UiItem) -> Unit,
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    private var list: List<UiItem> = emptyList()
    private val cache = object : LruCache<String, Bitmap>(80) {}

    init { setHasStableIds(true) }

    fun submit(items: List<UiItem>) {
        list = items
        notifyDataSetChanged()
    }

    override fun getItemCount() = list.size
    override fun getItemId(position: Int) = list[position].item.shortcode.hashCode().toLong()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge)
        val title: TextView = v.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.07f else 1f).scaleY(if (has) 1.07f else 1f).setDuration(120).start()
        }
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ui = list[position]
        h.title.text = ui.item.title ?: ui.item.shortcode
        h.badge.text = when {
            ui.state == St.DOWNLOADING -> "⬇ baixando"
            ui.state == St.ERROR -> "⚠ erro"
            ui.item.watched -> "✔ visto"
            ui.state == St.READY -> "● novo"
            else -> "… na fila"
        }
        h.itemView.alpha = if (ui.item.watched) 0.6f else 1f

        val t = ui.thumb
        if (t != null) {
            val key = t.absolutePath + t.lastModified()
            val bmp = cache.get(key) ?: BitmapFactory.decodeFile(t.absolutePath)?.also { cache.put(key, it) }
            h.thumb.setImageBitmap(bmp)
        } else {
            h.thumb.setImageDrawable(null)
        }

        h.itemView.setOnClickListener { onPlay(ui) }
        h.itemView.setOnLongClickListener { onMenu(ui); true }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                onMenu(ui); true
            } else false
        }
    }
}
