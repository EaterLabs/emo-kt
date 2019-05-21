package me.eater.emo.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Run function on IO thread
 */
suspend fun <T>io(
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): T = GlobalScope.async(Dispatchers.IO, start, block).await()

/**
 * like .forEach but in parallel, amount of processes is determined by [parallel]
 */
suspend fun <T> parallel(items: Iterable<T>, parallel: Int = 10, call: suspend (T) -> Unit) {
    val channel = Channel<T>()
    val jobs = arrayListOf<Deferred<Unit>>()

    repeat(parallel) {
        jobs.add(GlobalScope.async {
            for (item in channel) {
                call(item)
            }
        })
    }

    for (item in items) {
        channel.send(item)
    }

    channel.close()
    jobs.awaitAll()
}