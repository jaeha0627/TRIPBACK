package com.tripback.app.trip.recommendation

import com.tripback.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RecommendationHttpException(val status: Int) : IOException("HTTP $status")
class RecommendationConnectionException : IOException("추천 서버에 연결하지 못했습니다.")
class RecommendationTimeoutException : IOException("추천 서버 응답 시간이 초과됐습니다.")

/** One POST, no redirects or retries. Does not load credentials or invoke navigation. */
class HttpRecommendationRepository(
    baseUrl: String = "http://127.0.0.1:8080",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 190_000,
    private val totalTimeoutMs: Long = 195_000
) : RecommendationRepository {
    private val endpoint = URL(baseUrl.trimEnd('/') + "/api/v1/trips/recommend")
    private val busy = AtomicBoolean(false)
    init {
        require(endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null)
        require(endpoint.protocol == "https" ||
            (BuildConfig.DEBUG && endpoint.protocol == "http" && endpoint.host == "127.0.0.1"))
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && totalTimeoutMs > 0)
    }
    override suspend fun recommend(request: RecommendationRequest, scenario: FakeScenario): String {
        val body = RecommendationJson.request(request).toByteArray(Charsets.UTF_8)
        check(busy.compareAndSet(false, true)) { "추천 요청이 이미 진행 중입니다." }
        return suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val finished = AtomicBoolean(false)
            val task = FutureTask<Unit> {
                try {
                    if (!continuation.isActive) return@FutureTask
                    val conn = (endpoint.openConnection() as HttpURLConnection)
                    connection.set(conn)
                    conn.requestMethod = "POST"
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.instanceFollowRedirects = false
                    conn.useCaches = false
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setFixedLengthStreamingMode(body.size)
                    if (!continuation.isActive) return@FutureTask
                    conn.outputStream.use { it.write(body) }
                    val status = conn.responseCode
                    if (status !in 200..299) throw RecommendationHttpException(status)
                    val media = conn.contentType.orEmpty().lowercase().replace(" ", "")
                    if (media.substringBefore(';') != "application/json" ||
                        ("charset=" in media && media.substringAfter("charset=").substringBefore(';') !in setOf("utf-8", "\"utf-8\"")))
                        throw RecommendationFormatException()
                    val bytes = conn.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 262_144) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 262_145 - output.size()))
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > 262_144) throw RecommendationFormatException()
                    val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    RecommendationJson.response(raw, request)
                    if (finished.compareAndSet(false, true)) continuation.resume(raw)
                } catch (failure: Exception) {
                    val safe = when (failure) {
                        is RecommendationHttpException, is RecommendationFormatException -> failure
                        is java.nio.charset.CharacterCodingException -> RecommendationFormatException()
                        is SocketTimeoutException -> RecommendationTimeoutException()
                        else -> RecommendationConnectionException()
                    }
                    if (finished.compareAndSet(false, true)) continuation.resumeWithException(safe)
                } finally {
                    connection.getAndSet(null)?.disconnect()
                }
            }
            val timer = scheduler.schedule({
                if (finished.compareAndSet(false, true)) {
                    continuation.resumeWithException(RecommendationTimeoutException())
                    task.cancel(true)
                    workers.execute { connection.getAndSet(null)?.disconnect() }
                }
            }, totalTimeoutMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation {
                finished.set(true)
                task.cancel(true)
                workers.execute { connection.getAndSet(null)?.disconnect() }
                timer.cancel(false)
            }
            workers.execute { try { task.run() } finally { timer.cancel(false); busy.set(false) } }
        }
    }
    companion object {
        private val workers = Executors.newCachedThreadPool { r -> Thread(r, "recommendation-http").apply { isDaemon = true } }
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "recommendation-timeout").apply { isDaemon = true } }
    }
}
