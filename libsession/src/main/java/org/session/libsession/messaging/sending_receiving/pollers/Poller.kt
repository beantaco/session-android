package org.session.libsession.messaging.sending_receiving.pollers

import nl.komponents.kovenant.*
import nl.komponents.kovenant.functional.bind

import org.session.libsession.messaging.MessagingConfiguration
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveJob
import org.session.libsession.messaging.utilities.MessageWrapper
import org.session.libsession.snode.Snode
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeConfiguration

import org.session.libsignal.utilities.logging.Log
import org.session.libsignal.utilities.Base64

import java.security.SecureRandom
import java.util.*

private class PromiseCanceledException : Exception("Promise canceled.")

class Poller {
    private val userPublicKey = MessagingConfiguration.shared.storage.getUserPublicKey() ?: ""
    private var hasStarted: Boolean = false
    private val usedSnodes: MutableSet<Snode> = mutableSetOf()
    public var isCaughtUp = false

    // region Settings
    companion object {
        private val retryInterval: Long = 1 * 1000
    }
    // endregion

    // region Public API
    fun startIfNeeded() {
        if (hasStarted) { return }
        Log.d("Loki", "Started polling.")
        hasStarted = true
        setUpPolling()
    }

    fun stopIfNeeded() {
        Log.d("Loki", "Stopped polling.")
        hasStarted = false
        usedSnodes.clear()
    }
    // endregion

    // region Private API
    private fun setUpPolling() {
        if (!hasStarted) { return; }
        val thread = Thread.currentThread()
        SnodeAPI.getSwarm(userPublicKey).bind(SnodeAPI.messagePollingContext) {
            usedSnodes.clear()
            val deferred = deferred<Unit, Exception>(SnodeAPI.messagePollingContext)
            pollNextSnode(deferred)
            deferred.promise
        }.always {
            Timer().schedule(object : TimerTask() {

                override fun run() {
                    thread.run { setUpPolling() }
                }
            }, retryInterval)
        }
    }

    private fun pollNextSnode(deferred: Deferred<Unit, Exception>) {
        val swarm = SnodeConfiguration.shared.storage.getSwarm(userPublicKey) ?: setOf()
        val unusedSnodes = swarm.subtract(usedSnodes)
        if (unusedSnodes.isNotEmpty()) {
            val index = SecureRandom().nextInt(unusedSnodes.size)
            val nextSnode = unusedSnodes.elementAt(index)
            usedSnodes.add(nextSnode)
            Log.d("Loki", "Polling $nextSnode.")
            poll(nextSnode, deferred).fail { exception ->
                if (exception is PromiseCanceledException) {
                    Log.d("Loki", "Polling $nextSnode canceled.")
                } else {
                    Log.d("Loki", "Polling $nextSnode failed; dropping it and switching to next snode.")
                    SnodeAPI.dropSnodeFromSwarmIfNeeded(nextSnode, userPublicKey)
                    pollNextSnode(deferred)
                }
            }
        } else {
            isCaughtUp = true
            deferred.resolve()
        }
    }

    private fun poll(snode: Snode, deferred: Deferred<Unit, Exception>): Promise<Unit, Exception> {
        if (!hasStarted) { return Promise.ofFail(PromiseCanceledException()) }
        return SnodeAPI.getRawMessages(snode, userPublicKey).bind(SnodeAPI.messagePollingContext) { rawResponse ->
            isCaughtUp = true
            if (deferred.promise.isDone()) {
                task { Unit } // The long polling connection has been canceled; don't recurse
            } else {
                val messages = SnodeAPI.parseRawMessagesResponse(rawResponse, snode, userPublicKey)
                messages.forEach { message ->
                    val rawMessageAsJSON = message as? Map<*, *>
                    val base64EncodedData = rawMessageAsJSON?.get("data") as? String
                    val data = base64EncodedData?.let { Base64.decode(it) } ?: return@forEach
                    val job = MessageReceiveJob(MessageWrapper.unwrap(data), false)
                    JobQueue.shared.add(job)
                }
                poll(snode, deferred)
            }
        }
    }
    // endregion
}
