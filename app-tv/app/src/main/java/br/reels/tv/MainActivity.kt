package br.reels.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var mode = Mode.NEW
    private val tabViews = mutableMapOf<Mode, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mode = Mode.of(savedInstanceState?.getString("mode"))

        adapter = ItemAdapter(
            onPlay = { item -> play(item.item.shortcode) },
            onMenu = { item -> showItemMenu(item) },
        )
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, spanCount())
            adapter = this@MainActivity.adapter
            itemAnimator = null
        }
        buildTabs()
        findViewById<Button>(R.id.btnPlay).setOnClickListener { play(null) }
        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            ui.launch { Repo.refresh(retryErrors = true) }
            Toast.makeText(this, "Atualizando…", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }

        ui.launch {
            Repo.items.combine(Repo.status) { items, st -> items to st }.collect { (items, st) -> render(items, st) }
        }
        // Esta TV foi removida por outro aparelho → volta para a tela de pareamento
        ui.launch {
            Repo.revokedEvent.collect { n ->
                if (n > 0) {
                    pollJob?.cancel()
                    Toast.makeText(this@MainActivity, "Este aparelho foi removido da playlist.", Toast.LENGTH_LONG).show()
                    showPairing()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mode", mode.key)
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

    /** Número de colunas conforme a largura da tela (TV ≈ 5, celular em pé = 2). */
    private fun spanCount(): Int {
        val dm = resources.displayMetrics
        val pad = resources.getDimension(R.dimen.screen_pad_h) * 2
        val target = resources.getDimension(R.dimen.card_target)
        return ((dm.widthPixels - pad) / target).toInt().coerceIn(2, 8)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- Abas ----------

    private fun buildTabs() {
        val box = findViewById<LinearLayout>(R.id.tabs)
        box.removeAllViews()
        for (m in Mode.values()) {
            val tv = TextView(this).apply {
                text = m.label
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(9), dp(18), dp(9))
                setBackgroundResource(R.drawable.chip_bg)
                isFocusable = true
                isClickable = true
                setOnClickListener { selectTab(m) }
                // Na TV, só passar o foco pela aba já troca a lista (como nos apps de streaming)
                setOnFocusChangeListener { _, has -> if (has && mode != m) selectTab(m) }
            }
            box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            })
            tabViews[m] = tv
        }
        updateTabs()
    }

    private fun selectTab(m: Mode) {
        mode = m
        updateTabs()
        render(Repo.items.value, Repo.status.value)
        findViewById<RecyclerView>(R.id.grid).scrollToPosition(0)
    }

    private fun updateTabs(items: List<UiItem> = Repo.items.value) {
        for ((m, tv) in tabViews) {
            val n = items.count { m.matches(it.item) }
            tv.text = if (m == Mode.ALL) "${m.label} · $n" else if (n > 0) "${m.label} · $n" else m.label
            tv.isSelected = m == mode
            tv.setTextColor(if (m == mode) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    // ---------- Lista ----------

    private fun render(items: List<UiItem>, st: String) {
        val list = items.filter { mode.matches(it.item) }.let { if (mode == Mode.NEW) it else it.reversed() }
        adapter.submit(list)
        updateTabs(items)

        val empty = findViewById<TextView>(R.id.empty)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            items.isEmpty() -> "Sua playlist está vazia.\nSalve Reels pela extensão do Chrome ou pelo celular."
            mode == Mode.NEW -> "Tudo visto por aqui 🎉"
            mode == Mode.FAV -> "Nenhum favorito ainda.\nNo player, use o ♥ para favoritar."
            else -> "Nada aqui."
        }

        val btn = findViewById<Button>(R.id.btnPlay)
        btn.text = mode.playLabel
        btn.isEnabled = list.isNotEmpty()
        btn.alpha = if (list.isEmpty()) 0.45f else 1f

        val prontos = items.count { !it.item.watched && it.state == St.READY }
        val parts = mutableListOf<String>()
        if (items.isNotEmpty()) parts += "$prontos prontos para ver"
        if (items.any { it.state == St.DOWNLOADING }) parts += "baixando…"
        val erros = items.count { it.state == St.ERROR }
        if (erros > 0) parts += "$erros com erro"
        if (st.isNotBlank()) parts += st
        findViewById<TextView>(R.id.status).text = parts.joinToString("  ·  ")
    }

    // ---------- Telas ----------

    private fun showPairing() {
        findViewById<View>(R.id.main).visibility = View.GONE
        findViewById<View>(R.id.pairing).visibility = View.VISIBLE
        val pin = Repo.newPin()
        findViewById<TextView>(R.id.pin).text = pin.chunked(4).joinToString("-")
        pairJob?.cancel()
        pairJob = ui.launch {
            Repo.waitPairing(pin)
            Toast.makeText(this@MainActivity, "Conectado!", Toast.LENGTH_LONG).show()
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
                // 5 min: cada atualização lê todos os itens, e o plano grátis do Firestore tem limite diário de leituras
                delay(5 * 60_000)
            }
        }
        if (currentFocus == null) findViewById<Button>(R.id.btnPlay).requestFocus()
    }

    private fun play(start: String?) {
        val items = Repo.items.value
        // Se tocou num card, a fila é a da aba atual a partir dele
        val m = if (start != null && !mode.matches(Repo.item(start) ?: return)) Mode.ALL else mode
        val queue = items.filter { m.matches(it.item) }.let { if (m == Mode.NEW) it else it.reversed() }
        val startItem = queue.firstOrNull { it.item.shortcode == start }
        val fromStart = if (startItem != null) queue.dropWhile { it !== startItem } else queue
        if (fromStart.none { it.state == St.READY }) {
            if (startItem != null && startItem.state != St.DOWNLOADING) Repo.downloadNow(startItem.item.shortcode)
            Toast.makeText(this, "Ainda não há vídeo baixado para tocar. Aguarde um pouco…", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("queue", fromStart.map { it.item.shortcode }.toTypedArray())
        )
    }

    private fun showItemMenu(ui: UiItem) {
        val sc = ui.item.shortcode
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "▶  Assistir a partir daqui" to { play(sc) }
        options += (if (ui.item.favorite) "♡  Tirar dos favoritos" else "♥  Favoritar") to { Repo.toggleFavorite(sc); Unit }
        options += (if (ui.item.watched) "○  Marcar como não visto" else "✓  Marcar como visto") to {
            Repo.markWatched(sc, !ui.item.watched)
        }
        if (ui.state == St.ERROR || ui.state == St.QUEUED) options += "⬇  Baixar agora / tentar de novo" to { Repo.downloadNow(sc) }
        options += "↗  Abrir pelo player do Instagram" to {
            startActivity(Intent(this, EmbedActivity::class.java).putExtra("sc", sc))
        }
        options += "🗑  Remover da playlist" to { confirmRemove(sc) }

        val title = buildString {
            append(ui.item.title ?: sc)
            if (ui.error != null) append("\nErro: ").append(ui.error)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun confirmRemove(sc: String) {
        AlertDialog.Builder(this)
            .setMessage("Remover este Reel da playlist? Ele some de todos os aparelhos.")
            .setPositiveButton("Remover") { _, _ -> Repo.remove(sc) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Reels TV ${BuildConfig.VERSION_NAME}")
            .setItems(arrayOf("Desconectar este aparelho da playlist", "Cancelar")) { _, which ->
                if (which == 0) {
                    AlertDialog.Builder(this)
                        .setMessage("Desconectar? Os vídeos baixados aqui serão apagados (a playlist continua salva).")
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
    private val cache = object : LruCache<String, Bitmap>(120) {}

    init { setHasStableIds(true) }

    fun submit(items: List<UiItem>) {
        list = items
        notifyDataSetChanged()
    }

    override fun getItemCount() = list.size
    override fun getItemId(position: Int) = list[position].item.shortcode.hashCode().toLong()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val placeholder: View = v.findViewById(R.id.placeholder)
        val placeholderText: TextView = v.findViewById(R.id.placeholderText)
        val placeholderIcon: ImageView = v.findViewById(R.id.placeholderIcon)
        val badge: TextView = v.findViewById(R.id.badge)
        val fav: View = v.findViewById(R.id.fav)
        val title: TextView = v.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                .translationZ(if (has) 12f else 0f).setDuration(140).start()
        }
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ui = list[position]
        val it = ui.item
        h.title.text = it.title ?: ""
        h.placeholderText.text = it.shortcode
        h.fav.visibility = if (it.favorite) View.VISIBLE else View.GONE

        when {
            ui.state == St.DOWNLOADING -> badge(h, "baixando…", false)
            ui.state == St.ERROR -> badge(h, "erro", false)
            it.watched -> badge(h, "✓ visto", false)
            ui.state == St.READY -> badge(h, "NOVO", true)
            else -> badge(h, "na fila", false)
        }
        h.thumb.alpha = if (it.watched) 0.55f else 1f
        h.placeholderIcon.setImageResource(
            when (ui.state) {
                St.ERROR -> R.drawable.ic_error
                St.DOWNLOADING, St.QUEUED -> R.drawable.ic_download
                else -> R.drawable.ic_play
            }
        )

        val t = ui.thumb
        val bmp = if (t != null) {
            val key = t.absolutePath + t.lastModified()
            cache.get(key) ?: BitmapFactory.decodeFile(t.absolutePath)?.also { b -> cache.put(key, b) }
        } else null
        h.thumb.setImageBitmap(bmp)
        h.placeholder.visibility = if (bmp == null) View.VISIBLE else View.GONE

        h.itemView.setOnClickListener { onPlay(ui) }
        h.itemView.setOnLongClickListener { onMenu(ui); true }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                onMenu(ui); true
            } else false
        }
    }

    private fun badge(h: VH, text: String, accent: Boolean) {
        h.badge.text = text
        h.badge.setBackgroundResource(if (accent) R.drawable.pill_accent else R.drawable.pill_bg)
    }
}
