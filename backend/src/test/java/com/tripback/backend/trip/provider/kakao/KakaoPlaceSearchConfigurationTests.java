package com.tripback.backend.trip.provider.kakao;

import com.tripback.backend.trip.provider.PlaceSearchProvider;
import com.tripback.backend.trip.provider.UnavailableProviders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class KakaoPlaceSearchConfigurationTests {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(KakaoPlaceSearchConfiguration.class)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());
    }

    @Test
    void registersAdapterWithoutReadingOrRequiringCredentialsOrCallingTheNetwork() {
        context().run(application -> {
            assertNull(application.getStartupFailure());
            assertInstanceOf(KakaoPlaceSearchAdapter.class, application.getBean(PlaceSearchProvider.class));
            assertEquals(1, application.getBeansOfType(PlaceSearchProvider.class).size());
        });
    }

    @Test
    void explicitProviderOverrideRemainsPossibleWithoutDuplicateBeans() {
        PlaceSearchProvider override = UnavailableProviders.placeSearch();
        context().withBean(PlaceSearchProvider.class, () -> override).run(application -> {
            assertNull(application.getStartupFailure());
            assertSame(override, application.getBean(PlaceSearchProvider.class));
            assertEquals(1, application.getBeansOfType(PlaceSearchProvider.class).size());
        });
    }
}
