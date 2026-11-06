package com.bigdatasoup.smsstamp

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
    private lateinit var repo: SmsRepo
    private var all: List<Thread> = emptyList()
    private val adapter = ThreadAdapter()
    private val exec = Executors.newSingleThreadExecutor()
    private val selected = HashSet<Long>()
    private var contactsOnly = false
    private var mode: ActionMode? = null
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Sms.isDefault(this)) confirmDelete()
        else Toast.makeText(this, "SMS Stamp must be the default messaging app to delete", Toast.LENGTH_LONG).show()
    }
    private val modeCb = object : ActionMode.Callback {
        override fun onCreateActionMode(m: ActionMode, menu: Menu): Boolean { m.menuInflater.inflate(R.menu.menu_select, menu); return true }
        override fun onPrepareActionMode(m: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(m: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == R.id.action_delete) { confirmDelete(); return true }
            return false
        }
        override fun onDestroyActionMode(m: ActionMode) { mode = null; selected.clear(); adapter.notifyDataSetChanged() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        repo = SmsRepo(this)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<EditText>(R.id.search).doAfterTextChanged { filter(it?.toString() ?: "") }
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        contactsOnly = prefs.getBoolean("contactsOnly", false)
        val co = findViewById<TextView>(R.id.contacts_only)
        co.alpha = if (contactsOnly) 1f else 0.4f
        co.setOnClickListener {
            contactsOnly = !contactsOnly; co.alpha = if (contactsOnly) 1f else 0.4f
            prefs.edit().putBoolean("contactsOnly", contactsOnly).apply()
            filter(findViewById<EditText>(R.id.search).text.toString())
        }
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

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { handler.removeCallbacks(refresh); handler.postDelayed(refresh, 500) }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) load() }

    override fun onStart() { super.onStart(); contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer) }
    override fun onStop() { super.onStop(); contentResolver.unregisterContentObserver(observer) }

    private fun load() {
        if (all.isEmpty()) status("Loading…")
        exec.execute {
            val t = repo.threads()
            runOnUiThread { all = t; status(if (t.isEmpty()) "No SMS found." else null); filter(findViewById<EditText>(R.id.search).text.toString()) }
        }
    }

    private fun toggle(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        if (selected.isEmpty()) { mode?.finish(); return }
        mode?.title = "${selected.size} selected"
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) return
        if (!Sms.isDefault(this)) {
            roleLauncher.launch(if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
                else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName))
            return
        }
        val ids = selected.toList()
        val msgs = all.filter { it.threadId in ids }.sumOf { it.count }
        AlertDialog.Builder(this).setTitle("Delete ${ids.size} conversation(s)?")
            .setMessage("$msgs messages will be permanently deleted from this phone. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                exec.execute {
                    for (t in ids) contentResolver.delete(Uri.parse("content://mms-sms/conversations/$t"), null, null)
                    runOnUiThread { mode?.finish(); Toast.makeText(this, "Deleted ${ids.size} conversation(s)", Toast.LENGTH_SHORT).show(); load() }
                }
            }.show()
    }

    private fun filter(q: String) {
        val s = q.trim().lowercase()
        val base = if (contactsOnly) all.filter { it.name != null } else all
        adapter.items = if (s.isEmpty()) base else base.filter { it.display.lowercase().contains(s) || it.address.lowercase().contains(s) }
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
            h.itemView.foreground = if (t.threadId in selected) ColorDrawable(0x551E6FD9) else null
            h.itemView.setOnClickListener {
                if (mode != null) toggle(t.threadId)
                else startActivity(Intent(this@MainActivity, ConversationActivity::class.java)
                    .putExtra("thread", t.threadId).putExtra("name", t.display).putExtra("address", t.address))
            }
            h.itemView.setOnLongClickListener {
                if (mode == null) mode = startSupportActionMode(modeCb)
                toggle(t.threadId); true
            }
        }
    }
}
