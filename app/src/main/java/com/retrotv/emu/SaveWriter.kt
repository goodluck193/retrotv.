package com.retrotv.emu

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/** FIFO disk writes survive Activity destruction. */
object SaveWriter {
    private val budget = WorkBudget(3, minOf(Runtime.getRuntime().maxMemory() / 8, 16L * ResourceBudget.MIB))
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "RetroTV-saves") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    fun isIdle() = budget.idle()
    fun submit(context: Context, bytes: Long = 0, onRejected: () -> Unit = {}, block: () -> Unit): Deferred<Boolean> {
        val app = AppLanguage.context(context.applicationContext)
        if (!budget.acquire(bytes)) {
            onRejected()
            Toast.makeText(app, app.getString(R.string.save_busy), Toast.LENGTH_LONG).show()
            return CompletableDeferred(false)
        }
        return scope.async {
            try { block(); true } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(app, app.getString(R.string.save_failed, e.userMessage(app)), Toast.LENGTH_LONG).show() }
                false
            } finally { budget.release(bytes) }
        }
    }
    suspend fun flush() {
        check(withTimeoutOrNull(5000) { while (!budget.idle()) delay(20); true } == true) {
            "SAVE_BUSY"
        }
    }
}
