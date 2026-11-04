package com.tripback.app.trip.recommendation

import com.sun.net.httpserver.HttpServer
import com.tripback.app.trip.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class HttpRecommendationTests {
    private val request = RecommendationForm(parked=true,date="2026-11-07").request()
    private fun server(block: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon=true } }
            createContext("/api/v1/trips/recommend") { e -> try { block(e) } finally { e.close() } }
            start()
        }
    private fun reply(e:com.sun.net.httpserver.HttpExchange, body:String, code:Int=200, type:String="application/json") {
        val bytes=body.toByteArray(Charsets.UTF_8)
        e.responseHeaders.set("Content-Type",type)
        e.sendResponseHeaders(code,bytes.size.toLong());e.responseBody.use {it.write(bytes)}
    }
    private fun repo(s:HttpServer, read:Int=1000,total:Long=3000)=HttpRecommendationRepository("http://127.0.0.1:${s.address.port}",500,read,total)
    private fun receive(scenario:FakeScenario):RecommendationResponse = runBlocking {
        val s=server {reply(it,RecommendationFixtures.response(request,scenario))}
        try {RecommendationJson.response(repo(s).recommend(request,FakeScenario.BAD_JSON),request)} finally {s.stop(0)}
    }
    @Test fun postContainsExactFlatRequestAndOptionalFields() = runBlocking {
        val r=request.copy(companionType="FAMILY",childAgeGroup="TEEN",budgetPerPersonKrw=50000,
            mealPreferences=listOf("KOREAN"),walkingPreference="LOW",parkingPreferences=listOf("FREE"),
            amenities=listOf("ACCESSIBLE"),preferIndoorWhenRaining=true,
            mustVisitPlaceIds=listOf(ValidationPlaceId("KAKAO_LOCAL","fixture-001")))
        var body="";var method="";var content="";var remote=""
        val s=server {e ->
            body=e.requestBody.readBytes().toString(Charsets.UTF_8);method=e.requestMethod
            content=e.requestHeaders.getFirst("Content-Type").orEmpty();remote=e.remoteAddress.address.hostAddress.orEmpty()
            reply(e,RecommendationFixtures.response(r,FakeScenario.TWO))
        }
        try {
            repo(s).recommend(r,FakeScenario.BAD_JSON)
            assertEquals(r,RecommendationJson.readRequest(body));assertEquals("POST",method)
            assertTrue(content.startsWith("application/json"));assertEquals("127.0.0.1",remote)
            assertFalse(Json.parseToJsonElement(body).jsonObject.containsKey("preferences"))
        } finally {s.stop(0)}
    }
    @Test fun twoAlternatives() {assertEquals(2,receive(FakeScenario.TWO).alternatives.size)}
    @Test fun oneAlternative() {assertEquals(1,receive(FakeScenario.ONE).alternatives.size)}
    @Test fun zeroAlternatives() {assertTrue(receive(FakeScenario.ZERO).alternatives.isEmpty())}
    @Test fun unconfigured() {val r=receive(FakeScenario.UNCONFIGURED);assertEquals("AI_UNAVAILABLE",r.recommendationStatus);assertEquals(0,r.calls.modelGenerations)}
    @Test fun unknownNullEtaNeverAccepted() {
        val r=receive(FakeScenario.UNKNOWN);assertTrue(r.alternatives.isEmpty())
        assertTrue(r.rejectedAlternatives.all {it.proposal?.validation?.predictedReturnAt==null})
    }
    @Test fun infeasibleNeverAccepted() {assertTrue(receive(FakeScenario.INFEASIBLE).alternatives.isEmpty())}
    @Test fun demoEvidenceRemainsUnverified() {
        val r=receive(FakeScenario.TWO)
        assertTrue(r.candidates.all {it.data.dataMode==ValidationDataMode.DEMO_SIMULATED && it.data.verification=="UNVERIFIED"})
    }
    @Test fun httpErrorsAndRedirectsAreNotRetried() = runBlocking {
        for(code in listOf(400,403,404,413,415,500,502,302)) {
            val count=AtomicInteger();val s=server {count.incrementAndGet();reply(it,"secret must never appear",code)}
            try {
                val failure=try {repo(s).recommend(request,FakeScenario.TWO);error("expected HTTP error")} catch(e:RecommendationHttpException){e}
                assertEquals(code,failure.status);assertFalse(failure.message!!.contains("secret"));assertEquals(1,count.get())
            } finally {s.stop(0)}
        }
    }
    @Test fun malformedAndOversizedAndWrongContentTypeFail() = runBlocking {
        for((body,type) in listOf("{}" to "application/json", "{broken" to "application/json",
            "x".repeat(262145) to "application/json",RecommendationFixtures.response(request,FakeScenario.TWO) to "text/html")) {
            val s=server {reply(it,body,type=type)}
            try {try {repo(s).recommend(request,FakeScenario.TWO);fail("expected format error")}catch(_:RecommendationFormatException){}} finally{s.stop(0)}
        }
    }
    @Test fun connectionFailure() = runBlocking {
        val port=ServerSocket(0).use {it.localPort}
        try {HttpRecommendationRepository("http://127.0.0.1:$port",200,200,1000).recommend(request,FakeScenario.TWO);fail("expected connection failure")}
        catch(_:RecommendationConnectionException){}
    }
    @Test fun readTimeout() = runBlocking {
        val s=server {Thread.sleep(500);reply(it,RecommendationFixtures.response(request,FakeScenario.TWO))}
        try {try{repo(s,read=50).recommend(request,FakeScenario.TWO);fail("expected timeout")}catch(_:RecommendationTimeoutException){}}finally{s.stop(0)}
    }
    @Test fun overallTimeoutReturnsErrorNotCancellation() = runBlocking {
        val s=server {Thread.sleep(500);reply(it,RecommendationFixtures.response(request,FakeScenario.TWO))}
        try {try{repo(s,total=50).recommend(request,FakeScenario.TWO);fail("expected timeout")}catch(_:RecommendationTimeoutException){}}finally{s.stop(0)}
    }
    @Test fun cancellationIsPromptAndLateResponseIgnoredAndDuplicateBlocked() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1);val count=AtomicInteger()
        val s=server {count.incrementAndGet();entered.complete(Unit);release.await(2,TimeUnit.SECONDS);reply(it,RecommendationFixtures.response(request,FakeScenario.TWO))}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val session=RecommendationSession(scope,repo(s))
        try {
            session.submit(request,FakeScenario.TWO);withTimeout(2000){entered.await()}
            session.submit(request,FakeScenario.ONE);assertEquals(1,count.get())
            session.cancel();assertEquals(RecommendationPage.INPUT,session.state.value.page)
            release.countDown();delay(150);assertNull(session.state.value.response)
            assertTrue(session.state.value.error!!.contains("취소"))
        } finally {release.countDown();scope.cancel();s.stop(0)}
    }
    @Test fun endpointRejectsNonLoopbackHttpAndCredentials() {
        for(url in listOf("http://10.0.2.2:8080","http://localhost:8080","http://example.org","https://user:password@example.org"))
            assertThrows(IllegalArgumentException::class.java){HttpRecommendationRepository(url)}
    }
}
