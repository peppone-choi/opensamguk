package opensamguk.gameapi.security

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.net.http.HttpConnectTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** connect와 body-complete deadline은 서로 다른 경계다. 정상 body 크기도 유한하게 제한한다. */
internal class JdkServerAdmissionTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(ServerAdmissionDraftBudget.CONNECT_MILLIS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val nanoTime: () -> Long = System::nanoTime,
) : ServerAdmissionTransport {
    override fun fetch(uri: URI, token: String, startedNanos: Long, budgetNanos: Long): ServerAdmissionHttpResponse {
        val beforeSend = budgetNanos - (nanoTime() - startedNanos)
        if (beforeSend <= 0) throw ServerAdmissionRequestDeadlineException()
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofNanos(beforeSend))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .GET().build()
        val pending = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBodySubscriber() })
        try {
            val remaining = budgetNanos - (nanoTime() - startedNanos)
            if (remaining <= 0) throw ServerAdmissionRequestDeadlineException()
            val response = pending.get(remaining, TimeUnit.NANOSECONDS)
            // 완료된 상태/본문 오류를 deadline으로 덮지 않는다. 파싱과 최종 만료는 source가 검사한다.
            return ServerAdmissionHttpResponse(response.statusCode(), response.body())
        } catch (error: Exception) {
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            // 실제 연결 실패는 로컬 대기로 숨기지 않는다. 전체 deadline timeout만 별도로 표시한다.
            if (causes.none { it is HttpConnectTimeoutException } &&
                causes.any { it is TimeoutException || it is HttpTimeoutException }) {
                throw ServerAdmissionRequestDeadlineException(error)
            }
            throw error
        } finally {
            // cancel은 교환 종료 시도다. 늦은 완료는 caller의 deadline/round fence에서도 거절한다.
            if (!pending.isDone) pending.cancel(true)
        }
    }

    private class LimitedBodySubscriber : HttpResponse.BodySubscriber<String> {
        private val delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
        private lateinit var subscription: Flow.Subscription
        private var bytes = 0L
        private var ended = false

        override fun getBody(): CompletionStage<String> = delegate.body
        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            delegate.onSubscribe(value)
        }
        override fun onNext(items: List<ByteBuffer>) {
            if (ended) return
            bytes += items.sumOf { it.remaining().toLong() }
            if (bytes > ServerAdmissionDraftBudget.MAX_BODY_BYTES) {
                ended = true
                subscription.cancel()
                delegate.onError(IllegalStateException("server admission body limit"))
            } else delegate.onNext(items)
        }
        override fun onError(error: Throwable) {
            if (!ended) { ended = true; delegate.onError(error) }
        }
        override fun onComplete() {
            if (!ended) { ended = true; delegate.onComplete() }
        }
    }
}
