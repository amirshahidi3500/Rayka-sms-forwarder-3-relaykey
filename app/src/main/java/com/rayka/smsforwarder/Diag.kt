package com.rayka.smsforwarder

/** Last result of the "fetch commands from server" check, shown in the Buoy tab so problems are visible. */
object Diag {
    @Volatile var outgoing: String = "هنوز بررسی نشده (سرویس روشن است؟)"
    @Volatile var outgoingAt: Long = 0L

    fun note(msg: String) {
        outgoing = msg
        outgoingAt = System.currentTimeMillis()
    }
}
