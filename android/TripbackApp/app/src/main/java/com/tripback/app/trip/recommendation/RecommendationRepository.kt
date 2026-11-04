package com.tripback.app.trip.recommendation

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.time.Duration

/** Fake and HTTP transports share only the recommendation contract. */
fun interface RecommendationRepository {
    suspend fun recommend(request: RecommendationRequest, scenario: FakeScenario): String
}
class FakeRecommendationRepository(private val latencyMs: Long = 900) : RecommendationRepository {
    override suspend fun recommend(request: RecommendationRequest, scenario: FakeScenario): String {
        delay(latencyMs)
        return RecommendationFixtures.response(request,scenario)
    }
}

/** All places/coordinates/ETAs in these fixtures are simulated, even if a title resembles a real category. */
object RecommendationFixtures {
    fun response(r: RecommendationRequest, scenario: FakeScenario): String {
        if(scenario==FakeScenario.BAD_JSON) return "{broken"
        val keyword=r.targetAreaKeyword+" 관광"
        val at=r.departAt.toInstant().toString()
        fun strings(values: List<String>)=JsonArray(values.map(::JsonPrimitive))
        fun point(lat:Double,lon:Double)=buildJsonObject { put("latitude",lat); put("longitude",lon) }
        fun id(n:Int)=buildJsonObject { put("provider","KAKAO_LOCAL"); put("value","fixture-00$n") }
        fun source(provider:String)=buildJsonObject {
            put("provider",provider); put("dataMode","DEMO_SIMULATED"); put("verification","UNVERIFIED"); put("retrievedAt",at)
        }
        val ids=listOf("fixture-001","fixture-002")
        val missingRequired=r.mustVisitPlaceIds.any { it.value !in ids }
        val unavailable=scenario in setOf(FakeScenario.UNCONFIGURED,FakeScenario.MODEL_ERROR,FakeScenario.TIMEOUT,FakeScenario.SOURCES)
        val stops=listOf("시연 문화 공간","시연 카페 공간")
        val coords=listOf(point(r.targetCenter.latitude,r.targetCenter.longitude),point(r.targetCenter.latitude+0.001,r.targetCenter.longitude))
        val candidates=if(unavailable) emptyList() else (0..1).map { n -> buildJsonObject {
            put("originalKeyword",keyword); put("id",id(n+1)); put("name",stops[n]); put("address","가상 주소 · 실제 방문 장소 아님")
            put("coordinates",coords[n]); put("data",source("KAKAO_LOCAL")); put("searchAttemptedAt",at); put("visitStatus","NOT_VERIFIED")
        } }
        val order=if(r.mustVisitPlaceIds.isEmpty()) listOf(0,1) else listOf(0,1)
        fun alternative(reverse:Boolean, forced:String?=null):JsonObject {
            val indexes=if(reverse) order.reversed() else order
            val stay=if(forced=="INFEASIBLE") 240L else 30L
            val predicted=r.departAt.plusMinutes(stay*2+30)
            val margin=Duration.between(predicted.toInstant(),r.returnDeadline.toInstant()).toMinutes()
            val unknown=forced=="UNKNOWN"
            val feasible=!unknown && margin>=r.safetyBufferMin
            val feasibility=if(unknown) "UNKNOWN" else if(feasible) "FEASIBLE" else "INFEASIBLE"
            val visits=indexes.mapIndexed { i,n -> buildJsonObject {
                put("index",i); put("requestedId",id(n+1)); put("stayMinutes",stay)
                putJsonObject("confirmedPlace") { put("id",id(n+1)); put("name",stops[n]); put("address","가상 주소 · 실제 방문 장소 아님")
                    put("coordinates",coords[n]); put("data",source("KAKAO_LOCAL")) }
                put("identityStatus","MATCHED_SELECTED_ID"); put("visitStatus","NOT_VERIFIED"); put("searchStatus","FOUND")
                put("attemptedAt",at); put("reason",JsonNull); put("failure",JsonNull)
            } }
            val legs=(0..2).map { i -> buildJsonObject {
                val missing=unknown && i==2
                put("index",i)
                put("origin",if(i==0) point(r.origin.latitude,r.origin.longitude) else coords[indexes[i-1]])
                put("destination",if(i==2) point(r.origin.latitude,r.origin.longitude) else coords[indexes[i]])
                put("durationSeconds",if(missing) JsonNull else JsonPrimitive(600))
                put("status",if(missing) "ETA_MISSING" else "FOUND")
                put("data",if(missing) JsonNull else source("KAKAO_MOBILITY"))
                put("attemptedAt",at); put("reason",if(missing) JsonPrimitive("ETA_MISSING") else JsonNull)
                put("failure",if(missing) buildJsonObject { put("code","MISSING_ETA"); put("httpStatus",JsonNull) } else JsonNull)
            } }
            return buildJsonObject {
                put("planId",if(reverse) "ai-proposal-2" else "ai-proposal-1")
                put("title",if(reverse) "시연 코스 B" else "시연 코스 A")
                put("recommendationReason","가상 AI 설명입니다. 실제 장소·주차·가격·영업 정보를 검증하지 않았습니다.")
                put("orderedVisits",JsonArray(indexes.mapIndexed { i,n -> buildJsonObject {
                    put("index",i); put("id",id(n+1)); put("originalKeyword",keyword); put("stayMinutes",stay)
                } }))
                putJsonObject("validation") {
                    put("feasibility",feasibility); put("dataCoverage",if(unknown) "INCOMPLETE" else "CONTAINS_DEMO")
                    put("dataModes",strings(listOf("DEMO_SIMULATED"))); put("visits",JsonArray(visits)); put("legs",JsonArray(legs))
                    put("totalTravelMinutes",if(unknown) JsonNull else JsonPrimitive(30))
                    put("totalStayMinutes",stay*2)
                    put("predictedReturnAt",if(unknown) JsonNull else JsonPrimitive(predicted.toString()))
                    put("remainingMarginMin",if(unknown) JsonNull else JsonPrimitive(margin))
                    put("returnDeadlineMet",if(unknown) JsonNull else JsonPrimitive(margin>=0))
                    put("safetyBufferSatisfied",if(unknown) JsonNull else JsonPrimitive(feasible))
                    put("missingEtaLegIndexes",JsonArray(if(unknown) listOf(JsonPrimitive(2)) else emptyList()))
                    put("routeDataStatus",if(unknown) "INCOMPLETE" else "UNVERIFIED")
                    put("unknownReasons",strings(if(unknown) listOf("MISSING_ETA") else emptyList()))
                    put("calculationFailure",JsonNull); put("notice","MATHEMATICAL_RESULT_ONLY; CURRENT_DEPARTURE_ETAS_NOT_FUTURE_TRAFFIC; RETURN_NOT_GUARANTEED; VISITABILITY_NOT_VERIFIED")
                }
                put("feasibility",feasibility); put("dataModes",strings(listOf("DEMO_SIMULATED")))
                put("notices",strings(listOf("AI_REASON_UNVERIFIED","CURRENT_ETAS_ONLY_NOT_RETURN_GUARANTEE","VISITABILITY_NOT_VERIFIED")))
            }
        }
        val accepted=mutableListOf<JsonElement>(); val rejected=mutableListOf<JsonElement>()
        if(!unavailable && !missingRequired && scenario!=FakeScenario.ZERO) {
            val plans=if(scenario==FakeScenario.ONE) listOf(alternative(false)) else listOf(
                alternative(false,when(scenario){FakeScenario.UNKNOWN->"UNKNOWN";FakeScenario.INFEASIBLE->"INFEASIBLE";else->null}),
                alternative(true,when(scenario){FakeScenario.UNKNOWN->"UNKNOWN";FakeScenario.INFEASIBLE->"INFEASIBLE";else->null}))
            plans.forEachIndexed { i,p ->
                val f=p.getValue("feasibility").jsonPrimitive.content
                if(f=="FEASIBLE" && r.maximumOneWayMinutes>=10) accepted.add(p)
                else rejected.add(buildJsonObject { put("index",i); put("reason",if(f=="FEASIBLE") "MAXIMUM_ONE_WAY_EXCEEDED" else "VALIDATION_"+f); put("proposal",p) })
            }
        }
        if(missingRequired) rejected.add(buildJsonObject {put("index",-1);put("reason","REQUIRED_PLACE_UNAVAILABLE");put("proposal",JsonNull)})
        val status=when {
            scenario==FakeScenario.UNCONFIGURED -> "AI_UNAVAILABLE"
            unavailable -> "AI_FAILED"
            missingRequired -> "NO_ELIGIBLE_CANDIDATES"
            accepted.size==2 -> "COMPLETED"
            accepted.size==1 -> "PARTIAL"
            else -> "NO_FEASIBLE_ALTERNATIVES"
        }
        return buildJsonObject {
            put("recommendationStatus",status)
            putJsonObject("aiProviderStatus") { put("provider","FAKE")
                put("status",when(scenario){FakeScenario.UNCONFIGURED->"NOT_CONFIGURED";FakeScenario.MODEL_ERROR->"HTTP_ERROR";FakeScenario.TIMEOUT->"TIMEOUT";FakeScenario.SOURCES->"NOT_CONFIGURED";else->if(missingRequired)"NOT_CALLED" else "COMPLETED"})
                put("invocation",if(unavailable||missingRequired) "NOT_CALLED" else "FAKE_MODEL")
            }
            put("alternatives",JsonArray(accepted));put("rejectedAlternatives",JsonArray(rejected));put("candidates",JsonArray(candidates))
            put("searches",JsonArray(if(unavailable) emptyList() else listOf(buildJsonObject {
                put("keyword",keyword);put("status","FOUND");put("reason",JsonNull);put("failureCode",JsonNull);put("httpStatus",JsonNull);put("attemptedAt",at)
            })));put("excludedCandidates",JsonArray(emptyList()))
            putJsonObject("calls") {put("placeSearches",if(unavailable) 0 else 1+accepted.size*2+rejected.count { it.jsonObject["proposal"]!=JsonNull }*2)
                put("roadRoutes",(accepted.size+rejected.count {it.jsonObject["proposal"]!=JsonNull})*3)
                put("modelGenerations",if(unavailable||missingRequired) 0 else 1)}
            putJsonObject("conditionStatus") {
                put("targetCenterRadius","SERVER_FILTERED_CANDIDATES_WGS84_20KM")
                put("maximumOneWayMinutes","FIRST_OUTBOUND_AND_FINAL_RETURN_LEGS_ONLY")
                put("vehicleType","PREFERENCE_ONLY_DEFAULT_CAR_ROUTE_NOT_VEHICLE_SPECIFIC")
                listOf("interests","companionType","budgetPerPersonKrw","mealPreferences","walkingPreference","parkingPreferences","amenities","preferIndoorWhenRaining").forEach {put(it,"MODEL_HINT_ONLY_NOT_VERIFIED")}
                put("mustVisitPlaceIds","CHECKED_AGAINST_EVERY_ACCEPTED_PLAN")
            }
            put("limitations",strings(listOf("FAKE_RESPONSE_NO_EXTERNAL_CALLS","AI_REASON_UNVERIFIED_NOT_PROVIDER_FACTS",
                "CURRENT_DEPARTURE_ETAS_NOT_FUTURE_TRAFFIC","OPENING_PARKING_PRICES_WALKING_AMENITIES_NOT_VERIFIED","NO_NAVIGATION_DISPATCH_OR_PLAN_COMMIT")))
        }.toString()
    }
}
