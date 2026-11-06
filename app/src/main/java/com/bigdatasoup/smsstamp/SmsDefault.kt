package com.bigdatasoup.smsstamp

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat

object Sms {
    fun isDefault(ctx: Context) = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        else Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    @Suppress("DEPRECATION")
    fun manager(ctx: Context): SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    fun threadOf(ctx: Context, uri: Uri?): Long {
        if (uri == null) return -1
        return ctx.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1 } ?: -1
    }

    fun send(ctx: Context, address: String, body: String) {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        }
        val uri = ctx.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, v) ?: return
        val sm = manager(ctx)
        val parts = sm.divideMessage(body)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SentReceiver::class.java).setData(uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sent = ArrayList<PendingIntent?>(); for (i in parts.indices) sent.add(if (i == parts.size - 1) pi else null)
        sm.sendMultipartTextMessage(address, null, parts, sent, null)
    }

    fun notify(ctx: Context, address: String, body: String, threadId: Long) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("msgs", "Messages", NotificationManager.IMPORTANCE_HIGH))
        val name = SmsRepo(ctx).contactName(address) ?: address
        val open = Intent(ctx, ConversationActivity::class.java)
            .putExtra("thread", threadId).putExtra("name", name).putExtra("address", address)
        val pi = PendingIntent.getActivity(ctx, threadId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(threadId.toInt(), NotificationCompat.Builder(ctx, "msgs")
            .setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(name).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pi).setAutoCancel(true).build())
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        val first = parts[0]
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0); put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            val sub = intent.getIntExtra("subscription", -1); if (sub >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, sub)
        }
        val uri = ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v)
        Sms.notify(ctx, address, body, Sms.threadOf(ctx, uri))
    }
}

class SentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val uri = intent.data ?: return
        val type = if (resultCode == Activity.RESULT_OK) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        ctx.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null)
    }
}

/** Stores the MMS notification as a not-yet-downloaded inbox MMS, then starts the download (which replaces it). */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pdu = intent.getByteArrayExtra("data") ?: return
        val n = PduReader(pdu).headers()
        if (n.type != 0x82 || n.location == null) return
        val tid = if (n.from.isNotEmpty()) Telephony.Threads.getOrCreateThreadId(ctx, n.from) else -1L
        val v = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX); put(Telephony.Mms.MESSAGE_TYPE, 0x82)
            put(Telephony.Mms.CONTENT_LOCATION, n.location); put(Telephony.Mms.TRANSACTION_ID, n.trId)
            put(Telephony.Mms.EXPIRY, n.expiry); put(Telephony.Mms.MESSAGE_SIZE, n.size)
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000); put(Telephony.Mms.READ, 0); put(Telephony.Mms.SEEN, 0)
            if (tid >= 0) put(Telephony.Mms.THREAD_ID, tid)
        }
        val uri = ctx.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, v) ?: return
        if (n.from.isNotEmpty()) ctx.contentResolver.insert(Uri.parse("content://mms/${uri.lastPathSegment}/addr"), ContentValues().apply {
            put(Telephony.Mms.Addr.ADDRESS, n.from); put(Telephony.Mms.Addr.TYPE, 0x89); put(Telephony.Mms.Addr.CHARSET, 106)
        })
        Mms.download(ctx, n.location!!, n.trId, uri)
    }
}

class HeadlessSmsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !text.isNullOrBlank() && Sms.isDefault(this))
            for (a in to.split(',', ';')) if (a.isNotBlank()) Sms.send(this, a.trim(), text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
