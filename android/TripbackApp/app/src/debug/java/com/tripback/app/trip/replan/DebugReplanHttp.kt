package com.tripback.app.trip.replan

import com.tripback.app.trip.TripValidationJson
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Debug source set only. One explicit POST, no redirects, retries, discovery, providers or credentials. */
class DebugReplanHttp(
    baseUrl: String = "http://127.0.0.1:8080", private val clock: Clock = Clock.systemUTC(),
    private val connectTimeoutMs: Int = 5_000, private val readTimeoutMs: Int = 65_000,
    private val totalTimeoutMs: Long = 70_000, private val maxResponseBytes: Int = 1_048_576
) : DebugReplanRepository {
    private val base = URL(baseUrl).also {
        require(it.protocol == "http" && it.host == "127.0.0.1" && it.path in setOf("", "/"))
        require(it.userInfo == null && it.query == null && it.ref == null && it.port in -1..65535 && it.port != 0)
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && totalTimeoutMs > 0 && maxResponseBytes in 1..1_048_576)
    }.toExternalForm().trimEnd('/')
    private val busy = AtomicBoolean(false)

    override suspend fun register(selection: ReplanSelection, explicitlyRequested: Boolean): ReplanRegistration {
        if (!explicitlyRequested) throw ReplanException(ReplanError.EXPLICIT_REQUEST_REQUIRED)
        // Runtime UI fixtures are synthetic contract samples, never eligible for real server registration.
        if (selection.plan.planId.startsWith(REPLAN_FIXTURE_PLAN_PREFIX)) throw ReplanException(ReplanError.SELECTION_MISMATCH)
        val reply = post("/api/v1/trips/validate", TripValidationJson.request(selection.validationRequest), true)
        return ReplanJson.registration(reply.body, reply.headers, selection, base, clock.instant())
    }
    override suspend fun preview(plan: RegisteredReplanPlan, observation: ReplanObservation): ReplanPreview {
        require(plan.server == base) // A token may only go back to the server that issued it.
        try {
            val reply = post("/api/v1/trips/replan-preview", ReplanJson.request(plan, observation, clock.instant()), false)
            return ReplanJson.preview(reply.body, plan, observation, clock.instant())
        } catch (e: ReplanException) {
            if (e.error in setOf(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED, ReplanError.PLAN_REVISION_MISMATCH)) plan.reference.discard()
            throw e
        }
    }
    private class Reply(val body: String, val headers: Map<String, List<String>>)
    private suspend fun post(path: String, body: String, register: Boolean): Reply {
        val bytes = body.toByteArray(Charsets.UTF_8)
        require(bytes.size <= if (register) 65536 else 8192)
        check(busy.compareAndSet(false, true)) { "A replan request is already running" }
        return suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val finished = AtomicBoolean(false)
            fun fail(error: ReplanException) {
                if (finished.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(error)
            }
            val task = FutureTask<Unit> {
                try {
                    if (!continuation.isActive) return@FutureTask
                    val conn = URL(base + path).openConnection() as HttpURLConnection
                    connection.set(conn)
                    conn.requestMethod = "POST"; conn.doOutput = true; conn.useCaches = false
                    conn.connectTimeout = connectTimeoutMs; conn.readTimeout = readTimeoutMs; conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.setRequestProperty("Accept", "application/json"); conn.setRequestProperty("Accept-Encoding", "identity")
                    if (register) conn.setRequestProperty("X-Tripback-Register-Replan", "true")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    if (!continuation.isActive) return@FutureTask
                    conn.outputStream.use { it.write(bytes) }
                    val status = conn.responseCode
                    val limit = minOf(maxResponseBytes, if (status == 200 && register) 1_048_576 else if (status == 200) 262_144 else 16_384)
                    if (conn.contentLengthLong > limit) throw ReplanException(ReplanError.INVALID_RESPONSE)
                    val media = conn.contentType.orEmpty().lowercase().replace(" ", "")
                    val json = media.substringBefore(';') == "application/json"
                    if (status == 200 && (!json || ("charset=" in media &&
                                media.substringAfter("charset=").substringBefore(';') !in setOf("utf-8", "\"utf-8\""))))
                        throw ReplanException(ReplanError.INVALID_RESPONSE)
                    if (conn.getHeaderField("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity", true) })
                        throw ReplanException(ReplanError.INVALID_RESPONSE)
                    val stream = if (status == 200) conn.inputStream else conn.errorStream
                    val raw = stream?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            if (size > limit - output.size()) throw ReplanException(ReplanError.INVALID_RESPONSE)
                            output.write(buffer, 0, size)
                        }
                        output.toByteArray()
                    } ?: byteArrayOf()
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                    if (status != 200) throw ReplanException(ReplanJson.error(if (json) text else "", status), status)
                    val headers = conn.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value.toList() }
                    if (finished.compareAndSet(false, true) && continuation.isActive) continuation.resume(Reply(text, headers))
                } catch (e: Exception) {
                    fail(when (e) {
                        is ReplanException -> e
                        is SocketTimeoutException -> ReplanException(ReplanError.TIMEOUT)
                        is java.nio.charset.CharacterCodingException -> ReplanException(ReplanError.INVALID_RESPONSE)
                        else -> ReplanException(ReplanError.NETWORK)
                    })
                } finally { connection.getAndSet(null)?.disconnect() }
            }
            val timer = scheduler.schedule({
                fail(ReplanException(ReplanError.TIMEOUT)); task.cancel(true)
                workers.execute { connection.getAndSet(null)?.disconnect() }
            }, totalTimeoutMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation {
                finished.set(true); task.cancel(true); timer.cancel(false)
                workers.execute { connection.getAndSet(null)?.disconnect() }
            }
            workers.execute { try { task.run() } finally { timer.cancel(false); busy.set(false) } }
        }
    }
    companion object {
        private val workers = Executors.newCachedThreadPool { Thread(it, "debug-replan-http").apply { isDaemon = true } }
        private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "debug-replan-timeout").apply { isDaemon = true } }
    }
}
