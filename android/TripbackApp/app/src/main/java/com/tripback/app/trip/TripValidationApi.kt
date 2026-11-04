package com.tripback.app.trip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

class ValidationHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

/** All network work runs on IO. HTTP is limited to explicitly opted-in local debug destinations. */
class TripValidationApi(
    endpoint: String = "http://10.0.2.2:8080/api/v1/trips/validate",
    allowLocalHttp: Boolean = false,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 65_000,
    private val totalTimeoutMs: Long = 70_000,
    private val maxResponseBytes: Int = 1_048_576
) {
    private val url = URL(endpoint).also {
        require(it.userInfo == null && it.query == null && it.ref == null && it.path == "/api/v1/trips/validate")
        require(it.protocol == "https" || (allowLocalHttp && it.protocol == "http" && it.host in setOf("10.0.2.2", "127.0.0.1", "localhost", "[::1]")))
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && totalTimeoutMs > 0 && maxResponseBytes in 1..1_048_576)
    }

    suspend fun request(input: ValidationRequest): ValidationResult {
        val payload = TripValidationJson.request(input).toByteArray(Charsets.UTF_8)
        try {
            return withTimeout(totalTimeoutMs) {
                withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine { continuation ->
                        val connection = url.openConnection() as HttpURLConnection
                        continuation.invokeOnCancellation { connection.disconnect() }
                        try {
                            if (!continuation.isActive) return@suspendCancellableCoroutine
                            connection.requestMethod = "POST"
                            connection.connectTimeout = connectTimeoutMs
                            connection.readTimeout = readTimeoutMs
                            connection.instanceFollowRedirects = false
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                            connection.setRequestProperty("Accept", "application/json")
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            connection.setFixedLengthStreamingMode(payload.size)
                            connection.outputStream.use { it.write(payload) }
                            val code = connection.responseCode
                            if (code != 200) throw ValidationHttpException(code)
                            if (!connection.contentType?.substringBefore(';')?.trim().equals("application/json", true)
                                || !connection.getHeaderField("Content-Encoding").orEmpty().let { it.isEmpty() || it.equals("identity", true) }
                                || connection.contentLengthLong > maxResponseBytes) throw ValidationResponseException()
                            val bytes = connection.inputStream.use { stream ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val size = stream.read(buffer)
                                    if (size < 0) break
                                    if (size > maxResponseBytes - output.size()) throw ValidationResponseException()
                                    output.write(buffer, 0, size)
                                }
                                output.toByteArray()
                            }
                            val body = try {
                                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                            } catch (_: Exception) { throw ValidationResponseException() }
                            val result = TripValidationJson.response(body, input)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } finally { connection.disconnect() }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) { throw SocketTimeoutException("검증 요청 시간 초과") }
    }
}

fun validationErrorMessage(failure: Exception): String = when (failure) {
    is ValidationHttpException -> when (failure.statusCode) {
        400 -> "입력 오류: 좌표·날짜·시간·장소 ID·체류시간을 확인해 주세요."
        404 -> "개발용 검증 API가 비활성 상태이거나 경로가 등록되지 않았습니다."
        500 -> "서버 내부 오류입니다. 서버 상태를 확인한 후 다시 시도해 주세요."
        else -> "서버 요청 실패: HTTP ${failure.statusCode}"
    }
    is ValidationResponseException -> "응답 검증 실패: 검증 API JSON 계약을 확인해 주세요."
    is SocketTimeoutException -> "요청 시간이 초과되었습니다. 서버 연결과 처리 상태를 확인해 주세요."
    is IOException -> "서버 연결 실패: 개발 서버와 에뮬레이터 네트워크를 확인해 주세요."
    else -> "입력값을 확인하거나 다시 시도해 주세요."
}
