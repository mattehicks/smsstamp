package com.bigdatasoup.smsstamp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val perms = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
    private lateinit var repo: SmsRepo
    private var all: List<Thread> = emptyList()
    private val adapter = ThreadAdapter()
    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        repo = SmsRepo(this)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<EditText>(R.id.search).doAfterTextChanged { filter(it?.toString() ?: "") }
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) load()
        else requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) load()
        else status("SMS permission denied. Grant it in Settings > Apps > SMS Stamp > Permissions.")
    }

    private fun status(s: String?) {
        findViewById<TextView>(R.id.status).apply { text = s; visibility = if (s == null) View.GONE else View.VISIBLE }
    }

    private fun load() {
        status("Loading…")
        exec.execute {
            val t = repo.threads()
            runOnUiThread { all = t; status(if (t.isEmpty()) "No SMS found." else null); filter(findViewById<EditText>(R.id.search).text.toString()) }
        }
    }

    private fun filter(q: String) {
        val s = q.trim().lowercase()
        adapter.items = if (s.isEmpty()) all else all.filter { it.display.lowercase().contains(s) || it.address.lowercase().contains(s) }
        adapter.notifyDataSetChanged()
    }

    inner class ThreadAdapter : RecyclerView.Adapter<ThreadAdapter.VH>() {
        var items: List<Thread> = emptyList()
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name); val number: TextView = v.findViewById(R.id.number)
            val snippet: TextView = v.findViewById(R.id.snippet); val date: TextView = v.findViewById(R.id.date)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_thread, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val t = items[i]
            h.name.text = t.display
            h.number.text = if (t.name != null) "${t.address} · ${t.count} msgs" else "${t.count} msgs"
            h.snippet.text = t.snippet
            h.date.text = Fmt.short(t.date)
            h.itemView.setOnClickListener {
                startActivity(Intent(this@MainActivity, ConversationActivity::class.java)
                    .putExtra("thread", t.threadId).putExtra("name", t.display).putExtra("address", t.address))
            }
        }
    }
}
