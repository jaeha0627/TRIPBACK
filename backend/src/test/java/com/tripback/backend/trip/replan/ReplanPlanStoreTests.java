package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.integration.TripValidationResult;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.tripback.backend.trip.replan.ReplanApiFixtures.*;

class ReplanPlanStoreTests {
    @Test void capacityDoesNotEvictActivePlansAndExpiredSlotCanBeReused() {
        var clock = new MutableClock(); var store = new ReplanPlanStore(clock, Duration.ofMinutes(30), 1);
        var result = validation(new Providers(), NOW.plusSeconds(10800), 30);
        var first = store.registerServerValidation(result);
        assertEquals("REGISTERED", first.status());
        assertEquals("CAPACITY_EXCEEDED", store.registerServerValidation(result).status());
        assertNotNull(store.find(first.handle().planRef()));
        clock.at = NOW.plusSeconds(1800);
        assertNull(store.find(first.handle().planRef()));
        assertEquals("REGISTERED", store.registerServerValidation(result).status());
    }
    @Test void referencesAreUniqueUnpredictableAndRestartLosesThem() {
        var clock = new MutableClock(); var store = new ReplanPlanStore(clock, Duration.ofMinutes(30), 32);
        var result = validation(new Providers(), NOW.plusSeconds(10800), 30);
        Set<String> references = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            var handle = store.registerServerValidation(result).handle();
            assertTrue(handle.planRef().matches("[A-Za-z0-9_-]{43}")); assertTrue(references.add(handle.planRef()));
            assertEquals(1, handle.revision()); assertEquals(NOW.plusSeconds(1800), handle.expiresAt());
        }
        var restarted = new ReplanPlanStore(clock, Duration.ofMinutes(30), 32);
        references.forEach(ref -> assertNull(restarted.find(ref)));
    }
    @Test void demoAndMissingEtaCannotRegisterEvenIfMathematicalResultFits() {
        var store = new ReplanPlanStore(); var fake = new Providers();
        fake.demo = true;
        assertEquals("NOT_ELIGIBLE", store.registerServerValidation(validation(fake, NOW.plusSeconds(10800), 30)).status());
        fake.demo = false; fake.missing = true;
        assertEquals("NOT_ELIGIBLE", store.registerServerValidation(validation(fake, NOW.plusSeconds(10800), 30)).status());
    }
    @Test void forgedCalculationAndBrokenRoundTripCannotRegister() {
        var store = new ReplanPlanStore(); var result = validation(new Providers(), NOW.plusSeconds(10800), 30);
        var another = validation(new Providers(), NOW.plusSeconds(14400), 30);
        var forged = new TripValidationResult(result.input(), result.places(), result.routes(), another.calculation(), null);
        assertEquals("NOT_ELIGIBLE", store.registerServerValidation(forged).status());
        var routes = new ArrayList<>(result.routes()); routes.set(2, routes.get(0));
        assertEquals("NOT_ELIGIBLE", store.registerServerValidation(new TripValidationResult(
                result.input(), result.places(), routes, result.calculation(), null)).status());
    }
    @Test void concurrentRegistrationHonorsCapacity() throws Exception {
        var store = new ReplanPlanStore(new MutableClock(), Duration.ofMinutes(30), 4);
        var result = validation(new Providers(), NOW.plusSeconds(10800), 30);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ReplanPlanStore.Registration>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) tasks.add(workers.submit(() -> store.registerServerValidation(result)));
            int registered = 0;
            for (var task : tasks) if (task.get().handle() != null) registered++;
            assertEquals(4, registered);
        }
    }
}
