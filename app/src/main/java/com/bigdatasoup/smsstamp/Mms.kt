package com.bigdatasoup.smsstamp

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

class RawPart(val type: String, val name: String, val data: ByteArray)

class MmsHeaders {
    var type = 0; var trId: String? = null; var from = ""; val to = ArrayList<String>()
    var location: String? = null; var size = 0L; var expiry = 0L; var date = 0L; var subject: String? = null; var msgId: String? = null
    var contentType = ""; var bodyStart = -1
}

/** Minimal WAP/MMS PDU reader (OMA MMS encapsulation). */
class PduReader(private val b: ByteArray) {
    var i = 0
    private fun u(k: Int = i) = b[k].toInt() and 0xFF
    fun text(): String {
        val s = i; while (i < b.size && b[i].toInt() != 0) i++
        val t = String(b, s, i - s, Charsets.UTF_8); i++
        return t.removePrefix("\u007f").removePrefix("\"")
    }
    fun uintvar(): Int { var v = 0; while (i < b.size) { val x = u(i++); v = (v shl 7) or (x and 0x7F); if (x and 0x80 == 0) break }; return v }
    fun len(): Int { val v = u(i++); return if (v == 31) uintvar() else v }
    fun longInt(): Long { val n = u(i++); var v = 0L; repeat(n) { v = (v shl 8) or u(i++).toLong() }; return v }
    fun integer(): Long { val v = u(); return if (v >= 128) { i++; (v and 0x7F).toLong() } else longInt() }
    fun encString(): String {
        if (u() > 31) return text()
        val l = len(); val end = i + l
        if (u() >= 128) i++ else longInt()
        val t = text(); i = end; return t
    }
    fun skip() { val v = u(); when { v >= 128 -> i++; v <= 31 -> { val l = len(); i += l }; else -> text() } }

    private fun wellKnown(v: Int) = when (v) {
        0x03 -> "text/plain"; 0x1D -> "image/gif"; 0x1E -> "image/jpeg"; 0x20 -> "image/png"
        0x23 -> "application/vnd.wap.multipart.mixed"; 0x33 -> "application/vnd.wap.multipart.related"
        else -> "application/octet-stream"
    }

    /** Returns media type, name param, charset param. */
    fun contentType(): Triple<String, String?, Long> {
        val v = u()
        if (v >= 128) { i++; return Triple(wellKnown(v and 0x7F), null, 106) }
        if (v > 31) return Triple(text(), null, 106)
        val l = len(); val end = i + l
        val media = if (u() >= 128) wellKnown(u(i++) and 0x7F) else text()
        var name: String? = null; var cs = 106L
        while (i < end) {
            val tok = u()
            if (tok >= 128) {
                i++
                when (tok and 0x7F) { 0x01 -> cs = integer(); 0x05, 0x06, 0x17, 0x18 -> name = text(); else -> skip() }
            } else { text(); skip() }
        }
        i = end
        return Triple(media, name, cs)
    }

    fun headers(): MmsHeaders {
        val h = MmsHeaders()
        try {
            while (i < b.size) {
                val f = u()
                if (f < 128) { text(); skip(); continue }
                i++
                when (f) {
                    0x8C -> h.type = u(i++)
                    0x98 -> h.trId = text()
                    0x83 -> h.location = text()
                    0x8B -> h.msgId = text()
                    0x8E -> h.size = longInt()
                    0x85 -> h.date = longInt()
                    0x96 -> h.subject = encString()
                    0x97, 0x82 -> h.to.add(encString().substringBefore("/TYPE="))
                    0x88 -> { val l = len(); val end = i + l; val tok = u(i++); val v = longInt(); h.expiry = if (tok == 0x80) v else System.currentTimeMillis() / 1000 + v; i = end }
                    0x89 -> { val l = len(); val end = i + l; if (u() == 0x80) { i++; h.from = encString().substringBefore("/TYPE=") }; i = end }
                    0x84 -> { h.contentType = contentType().first; h.bodyStart = i; return h }
                    else -> skip()
                }
            }
        } catch (e: IndexOutOfBoundsException) { }
        return h
    }

    fun parts(): List<RawPart> {
        val out = ArrayList<RawPart>()
        try {
            val n = uintvar()
            repeat(n) { k ->
                val hl = uintvar(); val dl = uintvar(); val hs = i
                val (type, pname, cs) = contentType()
                var name = pname
                while (i < hs + hl) {
                    val tok = u()
                    when {
                        tok == 0x8E -> { i++; name = name ?: text() }
                        tok == 0xC0 -> { i++; val cid = text().trim('<', '>'); name = name ?: cid }
                        tok >= 128 -> { i++; skip() }
                        else -> { text(); skip() }
                    }
                }
                i = hs + hl
                var data = b.copyOfRange(i, i + dl); i += dl
                if (type.startsWith("text/") && cs != 106L) {
                    val charset = when (cs) { 3L -> Charsets.US_ASCII; 4L -> Charsets.ISO_8859_1; 1015L -> Charsets.UTF_16; else -> Charset.defaultCharset() }
                    data = String(data, charset).toByteArray(Charsets.UTF_8)
                }
                out.add(RawPart(type, name ?: "part$k", data))
            }
        } catch (e: IndexOutOfBoundsException) { }
        return out
    }
}

object MmsPdu {
    private fun ByteArrayOutputStream.text(s: String) { write(s.toByteArray(Charsets.UTF_8)); write(0) }
    private fun ByteArrayOutputStream.uintvar(v: Int) {
        val g = ArrayList<Int>(); var x = v
        g.add(x and 0x7F); x = x ushr 7
        while (x > 0) { g.add((x and 0x7F) or 0x80); x = x ushr 7 }
        for (k in g.indices.reversed()) write(g[k])
    }

    fun sendReq(to: List<String>, parts: List<RawPart>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x8C); o.write(0x80)
        o.write(0x98); o.text("T" + java.lang.Long.toHexString(System.currentTimeMillis()))
        o.write(0x8D); o.write(0x92)
        o.write(0x89); o.write(1); o.write(0x81)
        for (a in to) { o.write(0x97); o.text("$a/TYPE=PLMN") }
        o.write(0x84); o.write(0xA3)
        o.uintvar(parts.size)
        for (p in parts) {
            val h = ByteArrayOutputStream()
            if (p.type == "text/plain") { h.write(3); h.write(0x83); h.write(0x81); h.write(0xEA) } else h.text(p.type)
            h.write(0x8E); h.text(p.name)
            o.uintvar(h.size()); o.uintvar(p.data.size); h.writeTo(o); o.write(p.data)
        }
        return o.toByteArray()
    }

    fun notifyResp(trId: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x8C); o.write(0x83)
        o.write(0x98); o.text(trId)
        o.write(0x8D); o.write(0x92)
        o.write(0x95); o.write(0x81)
        return o.toByteArray()
    }
}

object Mms {
    private const val AUTH = "com.bigdatasoup.smsstamp.files"
    private fun isText(t: String) = t == "text/plain" || t == "application/smil"
    fun normalize(a: String) = if (a.contains('@')) a else a.filter { it.isDigit() || it == '+' }

    private fun pduFile(ctx: Context, prefix: String): File =
        File(ctx.cacheDir, "mms/${prefix}_${System.nanoTime()}.pdu").apply { parentFile?.mkdirs() }

    private fun insertParts(ctx: Context, mid: String, parts: List<RawPart>) {
        for (p in parts) {
            val v = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, mid); put(Telephony.Mms.Part.CONTENT_TYPE, p.type)
                put(Telephony.Mms.Part.CONTENT_LOCATION, p.name); put(Telephony.Mms.Part.CONTENT_ID, "<${p.name}>")
                if (isText(p.type)) { put(Telephony.Mms.Part.CHARSET, 106); put(Telephony.Mms.Part.TEXT, String(p.data, Charsets.UTF_8)) }
            }
            val u = ctx.contentResolver.insert(Uri.parse("content://mms/$mid/part"), v) ?: continue
            if (!isText(p.type)) ctx.contentResolver.openOutputStream(u)?.use { it.write(p.data) }
        }
    }

    private fun insertAddr(ctx: Context, mid: String, address: String, type: Int) {
        ctx.contentResolver.insert(Uri.parse("content://mms/$mid/addr"), ContentValues().apply {
            put(Telephony.Mms.Addr.ADDRESS, address); put(Telephony.Mms.Addr.TYPE, type); put(Telephony.Mms.Addr.CHARSET, 106)
        })
    }

    fun download(ctx: Context, url: String, trId: String?, placeholder: Uri) {
        val f = pduFile(ctx, "in")
        val uri = FileProvider.getUriForFile(ctx, AUTH, f)
        val i = Intent(ctx, MmsDownloadedReceiver::class.java)
            .putExtra("file", f.path).putExtra("placeholder", placeholder.toString()).putExtra("trId", trId)
        val pi = PendingIntent.getBroadcast(ctx, f.name.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Sms.manager(ctx).downloadMultimediaMessage(ctx, url, uri, null, pi)
    }

    fun storeReceived(ctx: Context, data: ByteArray): Pair<MmsHeaders, Long>? {
        val r = PduReader(data); val h = r.headers()
        if (h.type != 0x84 || h.bodyStart < 0) return null
        val parts = r.parts()
        val tid = Telephony.Threads.getOrCreateThreadId(ctx, h.from.ifEmpty { "unknown" })
        val now = System.currentTimeMillis() / 1000
        val v = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX); put(Telephony.Mms.MESSAGE_TYPE, 0x84)
            put(Telephony.Mms.DATE, now); put(Telephony.Mms.DATE_SENT, if (h.date > 0) h.date else now)
            put(Telephony.Mms.READ, 0); put(Telephony.Mms.SEEN, 0); put(Telephony.Mms.THREAD_ID, tid)
            put(Telephony.Mms.CONTENT_TYPE, h.contentType); put(Telephony.Mms.MMS_VERSION, 0x12)
            h.msgId?.let { put(Telephony.Mms.MESSAGE_ID, it) }; h.trId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            h.subject?.let { put(Telephony.Mms.SUBJECT, it) }
        }
        val uri = ctx.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, v) ?: return null
        val mid = uri.lastPathSegment ?: return null
        if (h.from.isNotEmpty()) insertAddr(ctx, mid, h.from, 0x89)
        for (a in h.to) insertAddr(ctx, mid, a, 0x97)
        insertParts(ctx, mid, parts)
        return Pair(h, tid)
    }

    fun sendAck(ctx: Context, trId: String) {
        val f = pduFile(ctx, "ack"); f.writeBytes(MmsPdu.notifyResp(trId))
        Sms.manager(ctx).sendMultimediaMessage(ctx, FileProvider.getUriForFile(ctx, AUTH, f), null, null, null)
    }

    fun send(ctx: Context, address: String, parts: List<RawPart>) {
        val to = normalize(address)
        val tid = Telephony.Threads.getOrCreateThreadId(ctx, address)
        val v = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX); put(Telephony.Mms.MESSAGE_TYPE, 0x80)
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000); put(Telephony.Mms.READ, 1); put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.THREAD_ID, tid); put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.mixed"); put(Telephony.Mms.MMS_VERSION, 0x12)
        }
        val uri = ctx.contentResolver.insert(Telephony.Mms.Outbox.CONTENT_URI, v) ?: return
        val mid = uri.lastPathSegment ?: return
        insertAddr(ctx, mid, address, 0x97)
        insertParts(ctx, mid, parts)
        val f = pduFile(ctx, "out"); f.writeBytes(MmsPdu.sendReq(listOf(to), parts))
        val i = Intent(ctx, MmsSentReceiver::class.java).setData(uri).putExtra("file", f.path)
        val pi = PendingIntent.getBroadcast(ctx, f.name.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Sms.manager(ctx).sendMultimediaMessage(ctx, FileProvider.getUriForFile(ctx, AUTH, f), null, null, pi)
    }
}

class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        val pending = goAsync()
        kotlin.concurrent.thread {
            val f = File(intent.getStringExtra("file") ?: "")
            try {
                val stored = if (ok && f.length() > 0) Mms.storeReceived(ctx, f.readBytes()) else null
                if (stored != null) {
                    intent.getStringExtra("placeholder")?.let { ctx.contentResolver.delete(Uri.parse(it), null, null) }
                    intent.getStringExtra("trId")?.let { Mms.sendAck(ctx, it) }
                    Sms.notify(ctx, stored.first.from, "\uD83D\uDCF7 Picture message", stored.second)
                } else Sms.notify(ctx, "SMS Stamp", "A picture message failed to download", -1)
            } finally { f.delete(); pending.finish() }
        }
    }
}

class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val box = if (resultCode == Activity.RESULT_OK) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED
        intent.data?.let { ctx.contentResolver.update(it, ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, box) }, null, null) }
        intent.getStringExtra("file")?.let { File(it).delete() }
    }
}
