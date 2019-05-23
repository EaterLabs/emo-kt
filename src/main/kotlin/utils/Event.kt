package me.eater.emo.utils

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Simple class that can hold callbacks for events
 */
class Event<T> : Iterable<MutableMap.MutableEntry<String, suspend (T) -> Unit>> {

    private val list = LinkedHashMap<String, suspend (T) -> Unit>()

    private var nextUnnamedIndex = 0L

    val size: Int @JvmName("size") get() = list.size
    val listeners: MutableCollection<MutableMap.MutableEntry<String, suspend (T) -> Unit>> get() = list.entries

    fun clear() = list.clear()

    override operator fun iterator() = list.iterator()

    @JvmName("add")
    operator fun plusAssign(handler: suspend (T) -> Unit) {
        list.put("${nextUnnamedIndex++}", handler)
    }

    @JvmName("put")
    operator fun set(name: String, handler: suspend (T) -> Unit) {
        list.put(name, handler)
    }

    @JvmName("remove")
    operator fun minusAssign(name: String) {
        list.remove(name)
    }

    @JvmName("handle")
    operator fun invoke(data: T) {
        GlobalScope.launch {
            handle(data)
        }
    }

    @JvmName("handleSuspended")
    suspend fun handle(data: T) {
        for ((_, value) in this@Event) value(data)
    }
}