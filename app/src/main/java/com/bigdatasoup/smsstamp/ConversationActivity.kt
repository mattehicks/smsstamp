package com.bigdatasoup.smsstamp

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.util.concurrent.Executors

class ConversationActivity : AppCompatActivity() {
    private lateinit var repo: SmsRepo
    private var all: List<Msg> = emptyList()
    private val adapter = MsgAdapter()
    private var showStamps = true
    private val exec = Executors.newSingleThreadExecutor()
    private var name = ""; private var address = ""; private var tid = -1L
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private var hits: List<Int> = emptyList(); private var cur = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)
        repo = SmsRepo(this)
        name = intent.getStringExtra("name") ?: ""; address = intent.getStringExtra("address") ?: ""
        tid = intent.getLongExtra("thread", -1)
        val data = intent.data
        if (data != null && data.scheme in setOf("sms", "smsto", "mms", "mmsto")) {
            address = Uri.decode(data.schemeSpecificPart ?: "").substringBefore('?').split(',', ';').first().trim()
            if (address.isEmpty()) { Toast.makeText(this, "No recipient", Toast.LENGTH_SHORT).show(); finish(); return }
            name = repo.contactName(address) ?: address
            tid = Telephony.Threads.getOrCreateThreadId(this, address)
        }
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.title = name; tb.subtitle = if (name != address) address else null
        setSupportActionBar(tb); supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        showStamps = prefs.getBoolean("stamps", true)
        val sw = findViewById<TextView>(R.id.stamps)
        sw.alpha = if (showStamps) 1f else 0.4f
        sw.setOnClickListener {
            showStamps = !showStamps; sw.alpha = if (showStamps) 1f else 0.4f
            prefs.edit().putBoolean("stamps", showStamps).apply(); adapter.notifyDataSetChanged()
        }
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        search = findViewById(R.id.search)
        search.doAfterTextChanged { filter(it?.toString() ?: "") }
        search.setOnEditorActionListener { _, actionId, ev ->
            val enter = actionId == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)
            if (enter && (ev == null || ev.action == KeyEvent.ACTION_DOWN)) nav(1)
            enter
        }
        findViewById<Button>(R.id.clear).setOnClickListener { if (search.text.isEmpty()) setSearchMode(false) else search.text.clear() }
        findViewById<Button>(R.id.prev).setOnClickListener { nav(-1) }
        findViewById<Button>(R.id.next).setOnClickListener { nav(1) }
        val compose = findViewById<EditText>(R.id.compose)
        compose.setText(intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
        findViewById<View>(R.id.send_bar).visibility = if (Sms.isDefault(this)) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.send).setOnClickListener {
            val text = compose.text.toString()
            if (text.isBlank()) return@setOnClickListener
            compose.text.clear()
            exec.execute { Sms.send(this, address, text); reload(true) }
        }
        reload(false)
    }

    private fun reload(toEnd: Boolean) {
        exec.execute {
            val m = repo.messages(tid)
            runOnUiThread { all = m; filter(search.text.toString()); if (toEnd && all.isNotEmpty()) list.scrollToPosition(all.size - 1) }
        }
    }

    private fun filter(q: String) {
        val s = q.trim().lowercase()
        adapter.items = all
        adapter.query = s
        hits = if (s.isEmpty()) emptyList() else all.indices.filter { all[it].body.lowercase().contains(s) }
        cur = hits.size - 1
        show()
        adapter.notifyDataSetChanged()
    }

    private fun setSearchMode(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        for (id in intArrayOf(R.id.search, R.id.clear, R.id.prev, R.id.next)) findViewById<View>(id).visibility = v
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) { search.requestFocus(); imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
        else { search.clearFocus(); imm.hideSoftInputFromWindow(search.windowToken, 0) }
    }

    private fun nav(d: Int) {
        if (hits.isEmpty()) return
        val old = adapter.current
        cur = (cur + d + hits.size) % hits.size
        show()
        adapter.notifyItemChanged(old); adapter.notifyItemChanged(adapter.current)
    }

    private fun show() {
        adapter.current = if (cur >= 0) hits[cur] else -1
        findViewById<TextView>(R.id.count).text = when {
            adapter.query.isEmpty() -> "${all.size} messages"
            hits.isEmpty() -> "No matches"
            else -> "Match ${cur + 1} of ${hits.size}"
        }
        if (cur >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(hits[cur], list.height / 3)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_conversation, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_search -> { setSearchMode(true); true }
        R.id.action_export_txt -> { export("txt"); true }
        R.id.action_export_csv -> { export("csv"); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun export(kind: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "sms_${safe}_${System.currentTimeMillis()}.$kind")
        f.writeText(if (kind == "csv") repo.exportCsv(name, address, all) else repo.exportTxt(name, address, all))
        val uri = FileProvider.getUriForFile(this, "com.bigdatasoup.smsstamp.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(if (kind == "csv") "text/csv" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Export ${f.name}"))
        Toast.makeText(this, "${all.size} messages exported", Toast.LENGTH_SHORT).show()
    }

    inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {
        var items: List<Msg> = emptyList(); var query = ""; var current = -1
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val row: LinearLayout = v.findViewById(R.id.row); val stamp: TextView = v.findViewById(R.id.stamp); val body: TextView = v.findViewById(R.id.body)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_message, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val m = items[i]
            h.row.gravity = if (m.outgoing) Gravity.END else Gravity.START
            h.body.setBackgroundResource(if (m.outgoing) R.drawable.bubble_out else R.drawable.bubble_in)
            h.body.text = highlight(m.body, i == current)
            h.stamp.visibility = if (showStamps) View.VISIBLE else View.GONE
            h.stamp.text = Fmt.stamp(m)
        }
        private fun highlight(body: String, isCurrent: Boolean): CharSequence {
            if (query.isEmpty()) return body
            val sp = SpannableString(body); val low = body.lowercase()
            var at = low.indexOf(query)
            while (at >= 0 && at + query.length <= body.length) {
                sp.setSpan(BackgroundColorSpan(if (isCurrent) 0xFFFF9800.toInt() else 0xFFFFEB3B.toInt()), at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(ForegroundColorSpan(Color.BLACK), at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                at = low.indexOf(query, at + query.length)
            }
            return sp
        }
    }
}
