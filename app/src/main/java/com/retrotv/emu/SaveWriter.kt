package com.retrotv.emu

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/** FIFO disk writes survive Activity destruction. */
object SaveWriter {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "RetroTV-saves") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    fun submit(context: Context, block: () -> Unit): Deferred<Boolean> = scope.async {
        try { block(); true } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(context.applicationContext, "Ошибка записи сохранения: ${e.message}", Toast.LENGTH_LONG).show() }
            false
        }
    }
    suspend fun flush() = withContext(dispatcher) { Unit }
}
