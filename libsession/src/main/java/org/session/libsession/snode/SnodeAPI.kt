@file:Suppress("NAME_SHADOWING")

package org.session.libsession.snode

import nl.komponents.kovenant.*
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map

import org.session.libsession.snode.utilities.getRandomElement

import org.session.libsignal.utilities.logging.Log
import org.session.libsignal.service.loki.api.utilities.HTTP
import org.session.libsignal.service.loki.utilities.prettifiedDescription
import org.session.libsignal.service.loki.utilities.retryIfNeeded
import org.session.libsignal.utilities.*

import java.security.SecureRandom

object SnodeAPI {
    val database = SnodeConfiguration.shared.storage
    val broadcaster = SnodeConfiguration.shared.broadcaster
    val sharedContext = Kovenant.createContext()
    val messageSendingContext = Kovenant.createContext()
    val messagePollingContext = Kovenant.createContext()

    internal var snodeFailureCount: MutableMap<Snode, Int> = mutableMapOf()
    internal var snodePool: Set<Snode>
        get() = database.getSnodePool()
        set(newValue) { database.setSnodePool(newValue) }

    // Settings
    private val maxRetryCount = 6
    private val minimumSnodePoolCount = 64
    private val minimumSwarmSnodeCount = 2
    private val seedNodePool: Set<String> = setOf( "https://storage.seed1.loki.network", "https://storage.seed3.loki.network", "https://public.loki.foundation" )
    internal val snodeFailureThreshold = 4
    private val targetSwarmSnodeCount = 2

    private val useOnionRequests = true

    internal var powDifficulty = 1

    // Error
    internal sealed class Error(val description: String) : Exception() {
        object Generic : Error("An error occurred.")
        object ClockOutOfSync : Error("The user's clock is out of sync with the service node network.")
        object RandomSnodePoolUpdatingFailed : Error("Failed to update random service node pool.")
    }

    // Internal API
    internal fun invoke(method: Snode.Method, snode: Snode, publicKey: String, parameters: Map<String, String>): RawResponsePromise {
        val url = "${snode.address}:${snode.port}/storage_rpc/v1"
        if (useOnionRequests) {
            return OnionRequestAPI.sendOnionRequest(method, parameters, snode, publicKey)
        } else {
            val deferred = deferred<Map<*, *>, Exception>()
            ThreadUtils.queue {
                val payload = mapOf( "method" to method.rawValue, "params" to parameters )
                try {
                    val json = HTTP.execute(HTTP.Verb.POST, url, payload)
                    deferred.resolve(json)
                } catch (exception: Exception) {
                    val httpRequestFailedException = exception as? HTTP.HTTPRequestFailedException
                    if (httpRequestFailedException != null) {
                        val error = handleSnodeError(httpRequestFailedException.statusCode, httpRequestFailedException.json, snode, publicKey)
                        if (error != null) { return@queue deferred.reject(exception) }
                    }
                    Log.d("Loki", "Unhandled exception: $exception.")
                    deferred.reject(exception)
                }
            }
            return deferred.promise
        }
    }

    internal fun getRandomSnode(): Promise<Snode, Exception> {
        val snodePool = this.snodePool
        if (snodePool.count() < minimumSnodePoolCount) {
            val target = seedNodePool.random()
            val url = "$target/json_rpc"
            Log.d("Loki", "Populating snode pool using: $target.")
            val parameters = mapOf(
                    "method" to "get_n_service_nodes",
                    "params" to mapOf(
                            "active_only" to true,
                            "fields" to mapOf( "public_ip" to true, "storage_port" to true, "pubkey_x25519" to true, "pubkey_ed25519" to true )
                    )
            )
            val deferred = deferred<Snode, Exception>()
            deferred<org.session.libsignal.service.loki.api.Snode, Exception>(SnodeAPI.sharedContext)
            ThreadUtils.queue {
                try {
                    val json = HTTP.execute(HTTP.Verb.POST, url, parameters, useSeedNodeConnection = true)
                    val intermediate = json["result"] as? Map<*, *>
                    val rawSnodes = intermediate?.get("service_node_states") as? List<*>
                    if (rawSnodes != null) {
                        val snodePool = rawSnodes.mapNotNull { rawSnode ->
                            val rawSnodeAsJSON = rawSnode as? Map<*, *>
                            val address = rawSnodeAsJSON?.get("public_ip") as? String
                            val port = rawSnodeAsJSON?.get("storage_port") as? Int
                            val ed25519Key = rawSnodeAsJSON?.get("pubkey_ed25519") as? String
                            val x25519Key = rawSnodeAsJSON?.get("pubkey_x25519") as? String
                            if (address != null && port != null && ed25519Key != null && x25519Key != null && address != "0.0.0.0") {
                                Snode("https://$address", port, Snode.KeySet(ed25519Key, x25519Key))
                            } else {
                                Log.d("Loki", "Failed to parse: ${rawSnode?.prettifiedDescription()}.")
                                null
                            }
                        }.toMutableSet()
                        Log.d("Loki", "Persisting snode pool to database.")
                        this.snodePool = snodePool
                        try {
                            deferred.resolve(snodePool.getRandomElement())
                        } catch (exception: Exception) {
                            Log.d("Loki", "Got an empty snode pool from: $target.")
                            deferred.reject(SnodeAPI.Error.Generic)
                        }
                    } else {
                        Log.d("Loki", "Failed to update snode pool from: ${(rawSnodes as List<*>?)?.prettifiedDescription()}.")
                        deferred.reject(SnodeAPI.Error.Generic)
                    }
                } catch (exception: Exception) {
                    deferred.reject(exception)
                }
            }
            return deferred.promise
        } else {
            return Promise.of(snodePool.getRandomElement())
        }
    }

    internal fun dropSnodeFromSwarmIfNeeded(snode: Snode, publicKey: String) {
        val swarm = database.getSwarm(publicKey)?.toMutableSet()
        if (swarm != null && swarm.contains(snode)) {
            swarm.remove(snode)
            database.setSwarm(publicKey, swarm)
        }
    }

    internal fun getSingleTargetSnode(publicKey: String): Promise<Snode, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(publicKey).map { it.shuffled(SecureRandom()).random() }
    }

    // Public API
    fun getTargetSnodes(publicKey: String): Promise<List<Snode>, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(publicKey).map { it.shuffled(SecureRandom()).take(targetSwarmSnodeCount) }
    }

    fun getSwarm(publicKey: String): Promise<Set<Snode>, Exception> {
        val cachedSwarm = database.getSwarm(publicKey)
        if (cachedSwarm != null && cachedSwarm.size >= minimumSwarmSnodeCount) {
            val cachedSwarmCopy = mutableSetOf<Snode>() // Workaround for a Kotlin compiler issue
            cachedSwarmCopy.addAll(cachedSwarm)
            return task { cachedSwarmCopy }
        } else {
            val parameters = mapOf( "pubKey" to publicKey )
            return getRandomSnode().bind {
                invoke(Snode.Method.GetSwarm, it, publicKey, parameters)
            }.map(SnodeAPI.sharedContext) {
                parseSnodes(it).toSet()
            }.success {
                database.setSwarm(publicKey, it)
            }
        }
    }

    fun getRawMessages(snode: Snode, publicKey: String): RawResponsePromise {
        val lastHashValue = database.getLastMessageHashValue(snode, publicKey) ?: ""
        val parameters = mapOf( "pubKey" to publicKey, "lastHash" to lastHashValue )
        return invoke(Snode.Method.GetMessages, snode, publicKey, parameters)
    }

    fun getMessages(publicKey: String): MessageListPromise {
        return retryIfNeeded(maxRetryCount) {
           getSingleTargetSnode(publicKey).bind(messagePollingContext) { snode ->
                getRawMessages(snode, publicKey).map(messagePollingContext) { parseRawMessagesResponse(it, snode, publicKey) }
            }
        }
    }

    fun sendMessage(message: SnodeMessage): Promise<Set<RawResponsePromise>, Exception> {
        val destination = message.recipient
        fun broadcast(event: String) {
            val dayInMs: Long = 86400000
            if (message.ttl != dayInMs && message.ttl != 4 * dayInMs) { return }
            broadcaster.broadcast(event, message.timestamp)
        }
        broadcast("calculatingPoW")
        return retryIfNeeded(maxRetryCount) {
            getTargetSnodes(destination).map(messageSendingContext) { swarm ->
                swarm.map { snode ->
                    broadcast("sendingMessage")
                    val parameters = message.toJSON()
                    retryIfNeeded(maxRetryCount) {
                        invoke(Snode.Method.SendMessage, snode, destination, parameters).map(messageSendingContext) { rawResponse ->
                            val json = rawResponse as? Map<*, *>
                            val powDifficulty = json?.get("difficulty") as? Int
                            if (powDifficulty != null) {
                                if (powDifficulty != SnodeAPI.powDifficulty && powDifficulty < 100) {
                                    Log.d("Loki", "Setting proof of work difficulty to $powDifficulty (snode: $snode).")
                                    SnodeAPI.powDifficulty = powDifficulty
                                }
                            } else {
                                Log.d("Loki", "Failed to update proof of work difficulty from: ${rawResponse.prettifiedDescription()}.")
                            }
                            rawResponse
                        }
                    }
                }.toSet()
            }
        }
    }

    // Parsing
    private fun parseSnodes(rawResponse: Any): List<Snode> {
        val json = rawResponse as? Map<*, *>
        val rawSnodes = json?.get("snodes") as? List<*>
        if (rawSnodes != null) {
            return rawSnodes.mapNotNull { rawSnode ->
                val rawSnodeAsJSON = rawSnode as? Map<*, *>
                val address = rawSnodeAsJSON?.get("ip") as? String
                val portAsString = rawSnodeAsJSON?.get("port") as? String
                val port = portAsString?.toInt()
                val ed25519Key = rawSnodeAsJSON?.get("pubkey_ed25519") as? String
                val x25519Key = rawSnodeAsJSON?.get("pubkey_x25519") as? String
                if (address != null && port != null && ed25519Key != null && x25519Key != null && address != "0.0.0.0") {
                    Snode("https://$address", port, Snode.KeySet(ed25519Key, x25519Key))
                } else {
                    Log.d("Loki", "Failed to parse snode from: ${rawSnode?.prettifiedDescription()}.")
                    null
                }
            }
        } else {
            Log.d("Loki", "Failed to parse snodes from: ${rawResponse.prettifiedDescription()}.")
            return listOf()
        }
    }

    fun parseRawMessagesResponse(rawResponse: RawResponse, snode: Snode, publicKey: String): List<*> {
        val messages = rawResponse["messages"] as? List<*>
        return if (messages != null) {
            updateLastMessageHashValueIfPossible(snode, publicKey, messages)
            removeDuplicates(publicKey, messages)
        } else {
            listOf<Map<*,*>>()
        }
    }

    private fun updateLastMessageHashValueIfPossible(snode: Snode, publicKey: String, rawMessages: List<*>) {
        val lastMessageAsJSON = rawMessages.lastOrNull() as? Map<*, *>
        val hashValue = lastMessageAsJSON?.get("hash") as? String
        val expiration = lastMessageAsJSON?.get("expiration") as? Int
        if (hashValue != null) {
            database.setLastMessageHashValue(snode, publicKey, hashValue)
        } else if (rawMessages.isNotEmpty()) {
            Log.d("Loki", "Failed to update last message hash value from: ${rawMessages.prettifiedDescription()}.")
        }
    }

    private fun removeDuplicates(publicKey: String, rawMessages: List<*>): List<*> {
        val receivedMessageHashValues = database.getReceivedMessageHashValues(publicKey)?.toMutableSet() ?: mutableSetOf()
        return rawMessages.filter { rawMessage ->
            val rawMessageAsJSON = rawMessage as? Map<*, *>
            val hashValue = rawMessageAsJSON?.get("hash") as? String
            if (hashValue != null) {
                val isDuplicate = receivedMessageHashValues.contains(hashValue)
                receivedMessageHashValues.add(hashValue)
                database.setReceivedMessageHashValues(publicKey, receivedMessageHashValues)
                !isDuplicate
            } else {
                Log.d("Loki", "Missing hash value for message: ${rawMessage?.prettifiedDescription()}.")
                false
            }
        }
    }

    // Error Handling
    internal fun handleSnodeError(statusCode: Int, json: Map<*, *>?, snode: Snode, publicKey: String? = null): Exception? {
        fun handleBadSnode() {
            val oldFailureCount = snodeFailureCount[snode] ?: 0
            val newFailureCount = oldFailureCount + 1
            snodeFailureCount[snode] = newFailureCount
            Log.d("Loki", "Couldn't reach snode at $snode; setting failure count to $newFailureCount.")
            if (newFailureCount >= snodeFailureThreshold) {
                Log.d("Loki", "Failure threshold reached for: $snode; dropping it.")
                if (publicKey != null) {
                    dropSnodeFromSwarmIfNeeded(snode, publicKey)
                }
                snodePool = snodePool.toMutableSet().minus(snode).toSet()
                Log.d("Loki", "Snode pool count: ${snodePool.count()}.")
                snodeFailureCount[snode] = 0
            }
        }
        when (statusCode) {
            400, 500, 503 -> { // Usually indicates that the snode isn't up to date
                handleBadSnode()
            }
            406 -> {
                Log.d("Loki", "The user's clock is out of sync with the service node network.")
                broadcaster.broadcast("clockOutOfSync")
                return Error.ClockOutOfSync
            }
            421 -> {
                // The snode isn't associated with the given public key anymore
                if (publicKey != null) {
                    Log.d("Loki", "Invalidating swarm for: $publicKey.")
                    dropSnodeFromSwarmIfNeeded(snode, publicKey)
                } else {
                    Log.d("Loki", "Got a 421 without an associated public key.")
                }
            }
            432 -> {
                // The PoW difficulty is too low
                val powDifficulty = json?.get("difficulty") as? Int
                if (powDifficulty != null) {
                    if (powDifficulty < 100) {
                        Log.d("Loki", "Setting proof of work difficulty to $powDifficulty (snode: $snode).")
                        SnodeAPI.powDifficulty = powDifficulty
                    } else {
                        handleBadSnode()
                    }
                } else {
                    Log.d("Loki", "Failed to update proof of work difficulty.")
                }
            }
            else -> {
                handleBadSnode()
                Log.d("Loki", "Unhandled response code: ${statusCode}.")
                return Error.Generic
            }
        }
        return null
    }


}

// Type Aliases
typealias RawResponse = Map<*, *>
typealias MessageListPromise = Promise<List<*>, Exception>
typealias RawResponsePromise = Promise<RawResponse, Exception>
