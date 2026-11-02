# Tripback — instructions for Codex and coding agents

## Canonical scope
Tripback is an AI-assisted, vehicle-connected ROUND-TRIP drive planner for Pleos Connect.
The canonical behavior reference is prototype/Tripback_차량화면_시안_v5.html and the team's final proposal v2 (the signed application is deliberately NOT stored in GitHub). Figma designs may be added later.
Do not rename the service to TIDEBACK, simplify it to a generic POI recommender, or silently change approved user flows.

## Code ownership
- android/: Kotlin Android app, user interface, NaviHelper/Vehicle adapters. Android developer.
- backend/: Java/Spring Boot, AI integration, candidate POI data, deterministic return-time checks. Backend developer.
- prototype/: existing HTML v5 reference. Leave unmodified unless expressly requested.
- docs/: API contracts, SDK capability test results, demo evidence, technical plans. Do not put personal information, names/phone numbers or signed application forms here.

## Non-negotiable engineering rules
1. Verify SDK coordinates, artifact versions, method names, permissions and callbacks against official Pleos documentation AND installed SDKs before writing calls. Never fabricate API surfaces.
2. Identify the source of every datum: VERIFIED_LIVE, VERIFIED_CACHED or DEMO_SIMULATED. Never display a simulated car event or SMS as successfully sent.
3. AI generates candidate itinerary proposals as STRUCTURED JSON; validated deterministic code checks place identity, coordinates, transit/route ETA, return deadline, safety buffer and required stops.
4. Never silently drop required stops or commit a plan change without the user's approval. For infeasible plans, show a truthful warning or alternative.
5. Support app/server network failure, bad JSON, missing API permissions and unavailable navigation SDK. Preserve last validated itinerary and declare demo fallback when used.
6. Respect driving safety and CarUxRestrictions. Avoid typing and complex itinerary editing in motion; do not claim speech works if unavailable.
7. Never commit access tokens, API keys, .env, Android local.properties, release signing key/keystore, contact information, signed proposal or personal identifiers.
8. Before editing, state files and plan. After editing, provide changed files, exact tests actually run, outcomes and blockers. Do not claim emulator integration or an APK is tested without evidence.
9. Use feature branches and reviewed pull requests for future functional changes. Do not nest separate .git repositories in android/ or backend/.
10. Keep Gradle wrapper and project build settings in Git. Use reproducible dependency versions.

## Next technical milestones
P0: Android Studio project compiles -> Pleos API 34 emulator boots -> Tripback app launches -> Spring Boot /health responds -> SDK capability matrix updated.
P1: structured trip input -> validated 2 itinerary alternatives -> select and inspect -> NaviHelper first route request.
P2: arrival/stay/delay -> deterministic return-time feasibility -> approve replan -> update next navigation route.
P3: finish round trip -> trip history -> working release APK + honest demo recording.
P4: expand verified real places, optional voice, SMS and advanced vehicle energy only after P0-P3 work.

## References
- docs/Tripback_Development_Plan.md
- docs/api-contract.md
- docs/sdk-capability-matrix.md
- docs/test-cases.md
- https://document.pleos.ai/
- https://appdevchallenge.auto-hmg.io/guide/submission
