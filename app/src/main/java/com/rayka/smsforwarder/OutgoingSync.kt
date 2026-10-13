package com.rayka.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The reverse direction: the server holds commands that need to reach a buoy, this phone
 * fetches them and sends them out as a real SMS.
 *
 * Online (main server): a dedicated thread keeps a "long request" open ("?wait=20"). The server answers
 * the moment a command appears, so a command reaches the buoy within a fraction of a second instead of
 * waiting for the next 3-second poll. After every SMS the phone tells the server it was sent.
 * Offline (local server): polled by the normal service tick ([pollLocal]).
 */
object OutgoingSync {

    private const val LONG_POLL_SEC = 20

    private val processLock = Any()
    private val pollerLock = Any()
    @Volatile private var pollerRunning = false
    private var pollerThread: Thread? = null
    @Volatile private var lastMainOkAt = 0L

    private class Ack(val remoteId: String, val status: String, val error: String?)

    private fun hasSendSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Starts the instant-delivery thread (idempotent). Called by the foreground service. */
    fun startPoller(context: Context) {
        val app = context.applicationContext
        synchronized(pollerLock) {
            if (pollerRunning) return
            pollerRunning = true
            pollerThread = Thread({ pollLoop(app) }, "rayka-cmd-poller").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    fun stopPoller() {
        synchronized(pollerLock) {
            pollerRunning = false
            pollerThread?.interrupt()
            pollerThread = null
        }
    }

    private fun pollLoop(ctx: Context) {
        var backoff = 1000L
        while (pollerRunning) {
            try {
                if (!hasSendSmsPermission(ctx)) { Diag.note("دسترسی ارسال پیامک (SMS) به اپ داده نشده"); Thread.sleep(3000); continue }
                if (!Prefs.onlineEnabled) { Diag.note("حالت آنلاین خاموش است"); Thread.sleep(3000); continue }
                if (Prefs.outgoingMainUrl.isBlank()) { Diag.note("آدرس سرور خروجی خالی است (تب بویه ← ذخیره)"); Thread.sleep(3000); continue }
                if (!NetworkUtils.isOnline(ctx)) { Diag.note("گوشی به اینترنت وصل نیست"); Thread.sleep(3000); continue }

                Diag.note("منتظر فرمان از سرور (اتصال زنده)…")
                val arr = NetworkUtils.getOutgoingMain(LONG_POLL_SEC)
                if (arr == null) {                       // error already described in Diag
                    Thread.sleep(backoff)
                    backoff = minOf(backoff * 2, 10_000L)
                    continue
                }
                backoff = 1000L
                lastMainOkAt = System.currentTimeMillis()
                val fresh = processArray(ctx, arr, MessageRecord.MODE_ONLINE)
                // The server still lists commands we already handled (our confirmation is on its way): don't hammer it
                if (arr.length() > 0 && fresh == 0) Thread.sleep(1500)
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }
    }

    /** One manual pass (the "check now" button): main server without waiting, then the local server. */
    fun fetchAndSend(context: Context) {
        if (!hasSendSmsPermission(context)) { Diag.note("دسترسی ارسال پیامک (SMS) به اپ داده نشده"); return }

        val onlineOn = Prefs.onlineEnabled
        val offlineOn = Prefs.offlineEnabled
        if (!onlineOn && !offlineOn) { Diag.note("هر دو حالت آنلاین و آفلاین خاموش است"); return }

        if (onlineOn) {
            if (!NetworkUtils.isOnline(context)) {
                Diag.note("گوشی به اینترنت وصل نیست")
            } else if (Prefs.outgoingMainUrl.isBlank()) {
                Diag.note("آدرس سرور خروجی خالی است (تب بویه ← ذخیره)")
            } else {
                val arr = NetworkUtils.getOutgoingMain()
                if (arr != null) {
                    lastMainOkAt = System.currentTimeMillis()
                    processArray(context, arr, MessageRecord.MODE_ONLINE)
                    return // got a real answer from the main server, no need to also hit local
                }
            }
        }

        if (offlineOn && Prefs.outgoingLocalUrl.isNotBlank()) {
            val arr = NetworkUtils.getOutgoingLocal()
            if (arr != null) {
                processArray(context, arr, MessageRecord.MODE_OFFLINE)
            }
        }
    }

    /** Local-server commands, called on every service tick. Skipped while the main server is answering. */
    fun pollLocal(context: Context) {
        if (!Prefs.offlineEnabled || Prefs.outgoingLocalUrl.isBlank()) return
        if (!hasSendSmsPermission(context)) return
        if (Prefs.onlineEnabled && System.currentTimeMillis() - lastMainOkAt < 30_000L) return
        val arr = NetworkUtils.getOutgoingLocal() ?: return
        processArray(context, arr, MessageRecord.MODE_OFFLINE)
    }

    /**
     * Sends every NEW command as an SMS first (so nothing waits for network round-trips), then reports
     * the results to the server. Returns how many new commands were handled.
     */
    private fun processArray(context: Context, arr: JSONArray, mode: String): Int {
        val db = DbHelper.get(context)
        val acks = ArrayList<Ack>()
        var fresh = 0
        synchronized(processLock) {           // the poller thread and the manual button must never handle the same command twice
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.optJSONObject(i) ?: continue
                val remoteId = obj.optString("id", obj.optString("_id", ""))
                val phone = obj.optString("phone", obj.optString("number", ""))
                val body = obj.optString("message", obj.optString("body", ""))
                if (remoteId.isBlank() || phone.isBlank() || body.isBlank()) continue

                if (db.outgoingAlreadyHandled(remoteId)) {
                    // The server still lists it as queued => our earlier confirmation was lost. Confirm again (never re-send the SMS).
                    if (mode == MessageRecord.MODE_ONLINE) {
                        val st = db.outgoingStatus(remoteId)
                        if (st != null) acks.add(Ack(remoteId, if (st == "SENT") "sent" else "failed", if (st == "SENT") null else "send failed"))
                    }
                    continue
                }

                val sent = sendSms(phone, body)
                db.insertOutgoing(remoteId, phone, body, mode, if (sent) "SENT" else "FAILED")
                fresh++
                if (mode == MessageRecord.MODE_ONLINE) {
                    acks.add(Ack(remoteId, if (sent) "sent" else "failed", if (sent) null else "send failed"))
                }
            }
        }
        for (a in acks) NetworkUtils.postOutgoingAck(a.remoteId, a.status, a.error)
        return fresh
    }

    private fun sendSms(phone: String, body: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, body, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
