package dev.apkpatcher

import java.util.concurrent.CancellationException

internal class OperationCancellation {
    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
    }

    fun check() {
        if (cancelled) throw CancellationException("Operation cancelled.")
    }
}
