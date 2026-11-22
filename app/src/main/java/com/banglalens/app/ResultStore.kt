package com.banglalens.app

import android.os.Handler
import android.os.Looper

/** Tiny in-memory store so the main screen can show the latest result and service state. */
object ResultStore {
    data class Result(val original: String, val bengali: String)

    @Volatile var last: Result? = null
        private set
    @Volatile var serviceRunning: Boolean = false
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun setResult(r: Result?) { last = r; notifyChanged() }
    fun setServiceRunning(v: Boolean) { serviceRunning = v; notifyChanged() }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyChanged() {
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }
    }
}
