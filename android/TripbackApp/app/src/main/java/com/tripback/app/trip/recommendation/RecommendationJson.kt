package com.tripback.app.trip.recommendation

import com.tripback.app.trip.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.*

class RecommendationFormatException : IOException("추천 JSON 계약과 일치하지 않습니다.")

object RecommendationJson {
    private fun point(p: ValidationPoint) = buildJsonObject { put("latitude",p.latitude); put("longitude",p.longitude) }
    private fun id(p: ValidationPlaceId) = buildJsonObject { put("provider",p.provider); put("value",p.value) }
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    fun request(r: RecommendationRequest): String = buildJsonObject {
        put("origin",point(r.origin)); put("targetAreaKeyword",r.targetAreaKeyword); put("targetCenter",point(r.targetCenter))
        put("departAt",r.departAt.toString()); put("returnDeadline",r.returnDeadline.toString()); put("safetyBufferMin",r.safetyBufferMin)
        put("companionType",r.companionType); put("interests",strings(r.interests)); put("vehicleType",r.vehicleType)
        put("maximumOneWayMinutes",r.maximumOneWayMinutes)
        r.childAgeGroup?.let { put("childAgeGroup",it) }; r.budgetPerPersonKrw?.let { put("budgetPerPersonKrw",it) }
        r.mealPreferences?.let { put("mealPreferences",strings(it)) }; r.walkingPreference?.let { put("walkingPreference",it) }
        r.parkingPreferences?.let { put("parkingPreferences",strings(it)) }; r.amenities?.let { put("amenities",strings(it)) }
        r.preferIndoorWhenRaining?.let { put("preferIndoorWhenRaining",it) }
        put("mustVisitPlaceIds",JsonArray(r.mustVisitPlaceIds.map(::id)))
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 16384) }

    fun readRequest(body: String): RecommendationRequest = guarded {
        val p=Json.parseToJsonElement(body).jsonObject
        RecommendationRequest(p.obj("origin").point(),p.text("targetAreaKeyword"),p.obj("targetCenter").point(),
            OffsetDateTime.parse(p.text("departAt")),OffsetDateTime.parse(p.text("returnDeadline")),p.int("safetyBufferMin"),
            p.text("companionType"),p.strings("interests"),p.text("vehicleType"),p.int("maximumOneWayMinutes"),
            p.optText("childAgeGroup"),p.optLong("budgetPerPersonKrw"),p.optStrings("mealPreferences"),p.optText("walkingPreference"),
            p.optStrings("parkingPreferences"),p.optStrings("amenities"),p.optBoolean("preferIndoorWhenRaining"),
            p["mustVisitPlaceIds"]?.takeUnless { it==JsonNull }?.jsonArray?.map { it.jsonObject.id() } ?: emptyList())
    }

    fun response(body: String, request: RecommendationRequest): RecommendationResponse = guarded {
        require(body.toByteArray(Charsets.UTF_8).size <= 262144)
        val p=Json.parseToJsonElement(body).jsonObject
        val status=p.text("recommendationStatus")
        require(status in setOf("COMPLETED","PARTIAL","NO_FEASIBLE_ALTERNATIVES","NO_CANDIDATES","NO_ELIGIBLE_CANDIDATES",
            "AI_UNAVAILABLE","AI_FAILED","INVALID_MODEL_OUTPUT","PROCESSING_TIMEOUT","CANCELLED","BUSY","FAILED"))
        fun alternative(e: JsonElement): RecommendationAlternative {
            val a=e.jsonObject
            val order=a.arr("orderedVisits").map { v -> v.jsonObject.let {
                OrderedVisit(it.int("index"),it.obj("id").id(),it.text("originalKeyword"),it.long("stayMinutes").also { n -> require(n in 0..240) })
            } }
            require(order.size in 1..5 && order.map { it.id }.distinct().size==order.size)
            order.forEachIndexed { i,v -> require(v.index==i) }
            require(order.map { it.id }.containsAll(request.mustVisitPlaceIds))
            val validationRequest=ValidationRequest(request.origin,request.departAt,request.returnDeadline,request.safetyBufferMin,
                order.map { ValidationVisit(it.originalKeyword,it.id,it.stayMinutes) })
            val validation=TripValidationJson.response(a.getValue("validation").toString(),validationRequest)
            val feasibility=ValidationFeasibility.valueOf(a.text("feasibility"))
            val modes=a.strings("dataModes").map(ValidationDataMode::valueOf).toSet()
            require(feasibility==validation.feasibility && modes==validation.dataModes)
            return RecommendationAlternative(a.text("planId"),a.text("title"),a.text("recommendationReason"),order,validation,
                feasibility,modes,a.strings("notices"))
        }
        val accepted=p.arr("alternatives").map(::alternative)
        require(accepted.size<=2 && accepted.map { it.planId }.distinct().size==accepted.size)
        accepted.forEach { a ->
            val v=a.validation
            require(a.feasibility==ValidationFeasibility.FEASIBLE && v.returnDeadlineMet==true && v.safetyBufferSatisfied==true)
            require(v.predictedReturnAt!=null && v.remainingMarginMin!=null && v.remainingMarginMin>=request.safetyBufferMin)
            require(v.predictedReturnAt.toInstant().plusSeconds(request.safetyBufferMin*60L)<=request.returnDeadline.toInstant())
            for(leg in listOf(v.legs.first(),v.legs.last())) require(leg.durationSeconds?.let { it/60 + if(it%60==0L) 0 else 1 }?.let { it<=request.maximumOneWayMinutes }==true)
        }
        if(status=="COMPLETED") require(accepted.size==2)
        if(status=="PARTIAL") require(accepted.size==1)
        if(status in setOf("NO_CANDIDATES","NO_ELIGIBLE_CANDIDATES","AI_UNAVAILABLE","AI_FAILED","INVALID_MODEL_OUTPUT","NO_FEASIBLE_ALTERNATIVES")) require(accepted.isEmpty())
        val rejected=p.arr("rejectedAlternatives").map { it.jsonObject.let { r ->
            RejectedAlternative(r.int("index",negative=true),r.text("reason"),r["proposal"]?.takeUnless { it==JsonNull }?.let(::alternative))
        } }
        val ai=p.obj("aiProviderStatus").let { AiProviderStatus(it.text("provider"),it.text("status"),it.text("invocation")) }
        val calls=p.obj("calls").let { RecommendationCalls(it.int("placeSearches"),it.int("roadRoutes"),it.int("modelGenerations")) }
        require(calls.placeSearches<=18 && calls.roadRoutes<=12 && calls.modelGenerations<=1)
        val candidates=p.arr("candidates").map { it.jsonObject.let { c ->
            RecommendationCandidate(c.text("originalKeyword"),c.obj("id").id(),c.text("name"),c.text("address",true),
                c.obj("coordinates").point(),c.obj("data").asSource(),Instant.parse(c.text("searchAttemptedAt")),c.text("visitStatus"))
        } }
        require(candidates.size<=30 && candidates.map { it.id }.distinct().size==candidates.size)
        accepted.forEach { a -> require(a.orderedVisits.all { visit -> candidates.any { it.id==visit.id } }) }
        RecommendationResponse(status,ai,accepted,rejected,candidates,
            p.arr("searches").map { it.jsonObject.let { s -> RecommendationSearch(s.text("keyword"),s.text("status"),s.optText("reason"),
                s.optText("failureCode"),s.optLong("httpStatus")?.toInt(),s.optText("attemptedAt")?.let(Instant::parse)) } },
            p.arr("excludedCandidates").map { it.jsonObject.let { e -> ExcludedCandidate(e.text("keyword"),e["id"]?.takeUnless { it==JsonNull }?.jsonObject?.id(),e.text("reason")) } },
            calls,p.obj("conditionStatus").mapValues { it.value.text() },p.strings("limitations"))
    }
    /** Metadata projection only; accepting a label does not authenticate any place or car event. */
    fun source(p: JsonObject): ValidationSource = p.asSource()
    private fun JsonObject.asSource(): ValidationSource {
        val mode=ValidationDataMode.valueOf(text("dataMode"))
        val verification=text("verification")
        require(verification==if(mode==ValidationDataMode.DEMO_SIMULATED) "UNVERIFIED" else "VALIDATED_PROVIDER_DATA")
        return ValidationSource(text("provider"),mode,verification,Instant.parse(text("retrievedAt")))
    }
    private fun JsonObject.point()=ValidationPoint(getValue("latitude").number().double,getValue("longitude").number().double)
    private fun JsonObject.id()=ValidationPlaceId(text("provider"),text("value"))
    private fun JsonElement.text(blank: Boolean=false): String=jsonPrimitive.let { require(it.isString && (blank||it.content.isNotBlank())); it.content }
    private fun JsonElement.number()=jsonPrimitive.also { require(!it.isString && it!=JsonNull) }
    private fun JsonObject.text(k:String,blank:Boolean=false)=getValue(k).text(blank)
    private fun JsonObject.obj(k:String)=getValue(k).jsonObject
    private fun JsonObject.arr(k:String)=getValue(k).jsonArray
    private fun JsonObject.long(k:String):Long=getValue(k).number().let { require(Regex("-?(?:0|[1-9][0-9]*)").matches(it.content)); it.long }
    private fun JsonObject.int(k:String,negative:Boolean=false)=long(k).also { require(it in (if(negative) -1L else 0L)..Int.MAX_VALUE.toLong()) }.toInt()
    private fun JsonObject.strings(k:String)=arr(k).map { it.text() }
    private fun JsonObject.optText(k:String)=get(k)?.takeUnless { it==JsonNull }?.text()
    private fun JsonObject.optLong(k:String)=get(k)?.takeUnless { it==JsonNull }?.let { long(k) }
    private fun JsonObject.optStrings(k:String)=get(k)?.takeUnless { it==JsonNull }?.let { strings(k) }
    private fun JsonObject.optBoolean(k:String)=get(k)?.takeUnless { it==JsonNull }?.number()?.let { requireNotNull(it.booleanOrNull) }
    private inline fun <T> guarded(block:()->T):T = try { block() } catch(_:Exception) { throw RecommendationFormatException() }
}
