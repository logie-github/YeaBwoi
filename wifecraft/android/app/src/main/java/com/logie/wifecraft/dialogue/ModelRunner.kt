package com.logie.wifecraft.dialogue

import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * One model call at a time, in priority order: the one shared model's throughput is the layer's budget. Answers are delivered
 * on the caller's thread, from [pump] (the scene's frame), so nothing outside the engine runs off the scene's thread. A
 * request that waits longer than its [ttl] seconds is dropped with a null answer. Without an engine every request answers null.
 */
class ModelRunner(private val engineOf: () -> DialogueEngine?) {
    constructor(engine: DialogueEngine?) : this({ engine })

    private class Job(val priority: Int, val seq: Long, val req: DialogueRequest, val kind: String, val expires: Double, val cb: (String?) -> Unit)

    private val pending = PriorityQueue<Job>(compareByDescending<Job> { it.priority }.thenBy { it.seq })
    private val done = ConcurrentLinkedQueue<() -> Unit>()
    private val busy = AtomicBoolean(false)
    private var seq = 0L
    private var clock = 0.0

    /** Requests waiting or running, of [kind] (null: any). */
    fun count(kind: String? = null): Int = pending.count { kind == null || it.kind == kind } + (if (busy.get() && (kind == null || runningKind == kind)) 1 else 0)
    private var runningKind: String? = null

    /** [urgency] goes to the runtime on the request ([DialogueRequest.priority]): the selected NPC and a sexual-stage pair first, a talking pair next, nearby, then ambient. */
    fun submit(priority: Int, kind: String, req: DialogueRequest, ttl: Double = 20.0, urgency: DialoguePriority = DialoguePriority.AMBIENT, cb: (String?) -> Unit) {
        pending += Job(priority, seq++, req.copy(priority = urgency), kind, clock + ttl, cb)
    }

    /** Drops the waiting requests of [kind] (their callbacks get null). A running one finishes and is handed over as usual. */
    fun cancel(kind: String) {
        val drop = pending.filter { it.kind == kind }
        pending.removeAll(drop.toSet())
        for (j in drop) j.cb(null)
    }

    /** Hands over finished answers and starts the next request; [now] is the caller's clock in seconds. */
    fun pump(now: Double = clock) {
        clock = now
        var progressed = true
        while (progressed) {
            progressed = false
            while (true) { val f = done.poll() ?: break; f(); progressed = true }
            while (pending.isNotEmpty() && pending.peek().expires < clock) { pending.poll().cb(null); progressed = true }
            if (!busy.get() && pending.isNotEmpty()) {
                val job = pending.poll()
                val engine = engineOf()
                // no model, or one still loading: the answer is nothing, at once (the asker waits its own pause before it asks again)
                if (engine == null || !engine.ready) { job.cb(null); progressed = true; continue }
                busy.set(true); runningKind = job.kind
                val finish = { r: String? -> done.add { busy.set(false); runningKind = null; job.cb(r) }; Unit }
                try {
                    suspend { engine.complete(job.req) }.startCoroutine(Continuation(EmptyCoroutineContext) { r -> finish(r.getOrNull()) })
                } catch (e: Throwable) { finish(null) }
                progressed = true
            }
        }
    }

    fun clear() { pending.clear(); done.clear() }
}
