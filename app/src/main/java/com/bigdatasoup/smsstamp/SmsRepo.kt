package com.bigdatasoup.smsstamp

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Part(val id: Long, val type: String, val name: String)

data class Msg(
    val id: Long, val threadId: Long, val address: String, val body: String,
    val date: Long, val dateSent: Long, val type: Int, val media: List<Part> = emptyList()
) {
    val outgoing get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX && type != Telephony.Sms.MESSAGE_TYPE_ALL
}

data class Thread(val threadId: Long, val address: String, val name: String?, val snippet: String, val date: Long, val count: Int) {
    val display get() = name ?: address
}

object Fmt {
    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)
    private val short = SimpleDateFormat("MM/dd/yy HH:mm", Locale.US)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    fun full(ms: Long) = full.format(Date(ms))
    fun short(ms: Long) = short.format(Date(ms))
    fun iso(ms: Long) = iso.format(Date(ms))
    fun utc(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC"); return f.format(Date(ms))
    }
    /** Stamp line shown above a message. date = time phone received/sent; date_sent = sender-side timestamp (received msgs only). */
    fun stamp(m: Msg): String {
        val dir = if (m.outgoing) "SENT" else "RECEIVED"
        val sb = StringBuilder("$dir ${full(m.date)}")
        if (!m.outgoing && m.dateSent > 0 && Math.abs(m.dateSent - m.date) > 1000) sb.append("\nsender clock ${full(m.dateSent)}")
        return sb.toString()
    }
}

class SmsRepo(private val ctx: Context) {
    private val nameCache = HashMap<String, String?>()

    fun contactName(address: String): String? {
        if (address.isBlank()) return null
        return nameCache.getOrPut(address) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } catch (e: Exception) { null }
        }
    }

    private val cols = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
        Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE)

    private fun read(sel: String?, args: Array<String>?, order: String): List<Msg> {
        val out = ArrayList<Msg>()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, cols, sel, args, order)?.use { c ->
            while (c.moveToNext()) out.add(Msg(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getLong(4), c.getLong(5), c.getInt(6)))
        }
        return out
    }

    fun threads(): List<Thread> {
        val all = read(null, null, "${Telephony.Sms.DATE} DESC")
        val seen = LinkedHashMap<Long, Thread>()
        val counts = HashMap<Long, Int>()
        for (m in all) {
            counts[m.threadId] = (counts[m.threadId] ?: 0) + 1
            if (!seen.containsKey(m.threadId)) seen[m.threadId] = Thread(m.threadId, m.address, contactName(m.address), m.body, m.date, 0)
        }
        ctx.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val tid = c.getLong(1); val d = c.getLong(2) * 1000; val t = seen[tid]
                counts[tid] = (counts[tid] ?: 0) + 1
                if (t == null) { val a = mmsAddress(c.getLong(0), c.getInt(3)); seen[tid] = Thread(tid, a, contactName(a), PIC, d, 0) }
                else if (d > t.date) seen[tid] = t.copy(snippet = PIC, date = d)
            }
        }
        return seen.values.map { it.copy(count = counts[it.threadId] ?: 0) }.sortedByDescending { it.date }
    }

    fun messages(threadId: Long): List<Msg> =
        (read("${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC") + mms(threadId)).sortedBy { it.date }

    private fun mms(threadId: Long): List<Msg> {
        val out = ArrayList<Msg>()
        ctx.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.DATE_SENT,
            Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE), "${Telephony.Mms.THREAD_ID}=?", arrayOf(threadId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val box = c.getInt(3)
                val parts = if (c.getInt(4) == 0x82) emptyList() else mmsParts(id)
                val text = parts.filter { it.first.type == "text/plain" }.joinToString("\n") { it.second ?: "" }
                val body = if (c.getInt(4) == 0x82) "[Picture message — not downloaded]" else text
                out.add(Msg(id, threadId, mmsAddress(id, box), body, c.getLong(1) * 1000, c.getLong(2) * 1000,
                    if (box == Telephony.Mms.MESSAGE_BOX_INBOX) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
                    parts.map { it.first }.filter { !it.type.startsWith("text/") && it.type != "application/smil" }))
            }
        }
        return out
    }

    private fun mmsParts(mid: Long): List<Pair<Part, String?>> {
        val out = ArrayList<Pair<Part, String?>>()
        ctx.contentResolver.query(Uri.parse("content://mms/part"), arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE,
            Telephony.Mms.Part.CONTENT_LOCATION, Telephony.Mms.Part.TEXT), "${Telephony.Mms.Part.MSG_ID}=?", arrayOf(mid.toString()), null)?.use { c ->
            while (c.moveToNext()) out.add(Pair(Part(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "attachment"), c.getString(3)))
        }
        return out
    }

    private fun mmsAddress(mid: Long, box: Int): String {
        val want = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) 0x89 else 0x97
        ctx.contentResolver.query(Uri.parse("content://mms/$mid/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null, null, null)?.use { c ->
            while (c.moveToNext()) { val a = c.getString(0) ?: ""; if (c.getInt(1) == want && a != "insert-address-token") return a }
        }
        return ""
    }

    companion object { const val PIC = "\uD83D\uDCF7 Picture" }

    fun exportTxt(name: String, address: String, msgs: List<Msg>): String {
        val sb = StringBuilder()
        sb.append("SMS export — $name ($address)\nExported ${Fmt.full(System.currentTimeMillis())}  (${Fmt.utc(System.currentTimeMillis())})\n")
        sb.append("Messages: ${msgs.size}\nTimestamps: 'date' = time recorded on this phone; 'date_sent' = timestamp supplied by sender/carrier.\n\n")
        for (m in msgs) {
            sb.append("[${Fmt.full(m.date)}] [${Fmt.utc(m.date)}] ${if (m.outgoing) "ME -> $name" else "$name -> ME"}")
            if (m.dateSent > 0) sb.append("  (date_sent ${Fmt.full(m.dateSent)})")
            sb.append("  id=${m.id}\n${m.body}\n\n")
        }
        return sb.toString()
    }

    fun exportCsv(name: String, address: String, msgs: List<Msg>): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("id,thread_id,direction,contact,address,date_local,date_iso,date_utc,date_epoch_ms,date_sent_iso,date_sent_epoch_ms,body\n")
        for (m in msgs) {
            sb.append(listOf(m.id.toString(), m.threadId.toString(), if (m.outgoing) "sent" else "received", q(name), q(address),
                q(Fmt.full(m.date)), Fmt.iso(m.date), q(Fmt.utc(m.date)), m.date.toString(),
                if (m.dateSent > 0) Fmt.iso(m.dateSent) else "", if (m.dateSent > 0) m.dateSent.toString() else "", q(m.body)).joinToString(",")).append("\n")
        }
        return sb.toString()
    }
}
