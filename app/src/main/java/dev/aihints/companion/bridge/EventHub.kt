package dev.aihints.companion.bridge

import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Fan-out of Python events to reviewer WebViews, keyed by note id.
 *
 * Events are also replayed for a few seconds so a WebView that connects a
 * moment after generation starts still shows the spinner.
 */
class EventHub {

    private val listeners = ConcurrentHashMap<Long, CopyOnWriteArraySet<ArrayBlockingQueue<String>>>()
    private val recent = ConcurrentHashMap<Long, ArrayDeque<Pair<Long, String>>>()

    fun subscribe(nid: Long): ArrayBlockingQueue<String> {
        val queue = ArrayBlockingQueue<String>(64)
        listeners.getOrPut(nid) { CopyOnWriteArraySet() }.add(queue)
        val cutoff = System.currentTimeMillis() - REPLAY_MS
        recent[nid]?.toList()?.forEach { (at, json) ->
            if (at >= cutoff) queue.offer(json)
        }
        return queue
    }

    fun unsubscribe(nid: Long, queue: ArrayBlockingQueue<String>) {
        listeners[nid]?.remove(queue)
        if (listeners[nid]?.isEmpty() == true) listeners.remove(nid)
    }

    fun publish(json: String) {
        val nid = try {
            JSONObject(json).optLong("nid", 0L)
        } catch (e: Exception) {
            0L
        }
        val ring = recent.getOrPut(nid) { ArrayDeque() }
        synchronized(ring) {
            ring.addLast(System.currentTimeMillis() to json)
            while (ring.size > REPLAY_COUNT) ring.removeFirst()
        }
        listeners[nid]?.forEach { queue ->
            if (!queue.offer(json)) {
                queue.poll()
                queue.offer(json)
            }
        }
    }

    companion object {
        private const val REPLAY_COUNT = 6
        private const val REPLAY_MS = 15_000L
    }
}
