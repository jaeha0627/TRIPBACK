package com.tripback.app.trip.replan

import androidx.compose.runtime.Composable
import com.tripback.app.trip.progress.TripJourney
import com.tripback.app.trip.recommendation.RecommendationRequest

/** Release slot has no UI, network, fixture or preview-state dependencies. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun JourneyReplanDebug(journey: TripJourney, request: RecommendationRequest?, raw: String?, selectedPlanId: String?) { }
