package me.eater.emo.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

suspend fun <T>io(
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): T = GlobalScope.async(Dispatchers.IO, start, block).await()

suspend fun <T> parallel(items: Iterable<T>, parallel: Int = 10, call: suspend (T) -> Unit) {
    val channel = Channel<T>()
    val jobs = arrayListOf<Job>()

    repeat(parallel) {
        jobs.add(GlobalScope.launch {
            for (item in channel) {
                call(item)
            }
        })
    }

    for (item in items) {
        channel.send(item)
    }

    channel.close()
    jobs.joinAll()
}

fun <T> slice(items: List<T>, from: Int = 0, to: Int = items.size): List<T> {
    val realTo = when {
        to < 0 -> items.size + to
        to <= items.size -> to
        else -> throw IllegalArgumentException()
    }


    val realFrom = when {
        from < 0 -> items.size + from
        from <= items.size -> from
        else -> throw IllegalArgumentException()
    }

    if (from < to) {
        throw IllegalArgumentException()
    }

    return (realFrom until realTo).map {
        items[it]
    }
}

fun <T> slice(items: Array<T>, from: Int = 0, to: Int = items.size): List<T> {
    return slice(items.toList(), from, to)
}