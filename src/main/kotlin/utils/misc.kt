package me.eater.emo.utils

import com.github.kittinunf.fuel.core.Deserializable
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import com.github.kittinunf.fuel.core.ResponseOf
import com.github.kittinunf.fuel.core.deserializers.ByteArrayDeserializer
import com.github.kittinunf.fuel.coroutines.await
import com.github.kittinunf.fuel.coroutines.awaitObjectResponse
import com.github.kittinunf.fuel.coroutines.awaitResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

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

class NoopDeserializer : Deserializable<Unit> {
    override fun deserialize(response: Response) = Unit
}

suspend inline fun Request.await(scope: CoroutineContext = Dispatchers.IO) =
    await(NoopDeserializer(), scope)

suspend inline fun Request.awaitResponse(scope: CoroutineContext = Dispatchers.IO): ResponseOf<Unit> =
    awaitResponse(NoopDeserializer(), scope)