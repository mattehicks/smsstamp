package com.bigdatasoup.smsstamp

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.concurrent.Executors

class ConversationActivity : AppCompatActivity() {
    private lateinit var repo: SmsRepo
    private var all: List<Msg> = emptyList()
    private val adapter = MsgAdapter()
    private var showStamps = true
    private val exec = Executors.newSingleThreadExecutor()
    private var name = ""; private var address = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)
        name = intent.getStringExtra("name") ?: ""; address = intent.getStringExtra("address") ?: ""
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.title = name; tb.subtitle = if (name != address) address else null
        setSupportActionBar(tb); supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = SmsRepo(this)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        showStamps = prefs.getBoolean("stamps", true)
        val sw = findViewById<MaterialSwitch>(R.id.stamps)
        sw.isChecked = showStamps
        sw.setOnCheckedChangeListener { _, on -> showStamps = on; prefs.edit().putBoolean("stamps", on).apply(); adapter.notifyDataSetChanged() }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        findViewById<EditText>(R.id.search).doAfterTextChanged { filter(it?.toString() ?: "") }
        val tid = intent.getLongExtra("thread", -1)
        exec.execute { val m = repo.messages(tid); runOnUiThread { all = m; filter("") } }
    }

    private fun filter(q: String) {
        val s = q.trim().lowercase()
        adapter.items = if (s.isEmpty()) all else all.filter { it.body.lowercase().contains(s) }
        adapter.query = s
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.count).text = if (s.isEmpty()) "${all.size} messages" else "${adapter.items.size} of ${all.size} match"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_conversation, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
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
        var items: List<Msg> = emptyList(); var query = ""
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val row: LinearLayout = v.findViewById(R.id.row); val stamp: TextView = v.findViewById(R.id.stamp); val body: TextView = v.findViewById(R.id.body)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_message, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val m = items[i]
            h.row.gravity = if (m.outgoing) Gravity.END else Gravity.START
            h.body.setBackgroundResource(if (m.outgoing) R.drawable.bubble_out else R.drawable.bubble_in)
            h.body.text = m.body
            h.stamp.visibility = if (showStamps) View.VISIBLE else View.GONE
            h.stamp.text = Fmt.stamp(m)
        }
    }
}
