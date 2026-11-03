package com.tripback.backend.trip.provider.kakao;

import com.tripback.backend.trip.provider.RoadRouteProvider;
import com.tripback.backend.trip.provider.UnavailableProviders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class KakaoRoadRouteConfigurationTests {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(KakaoRoadRouteConfiguration.class)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());
    }

    @Test
    void registersAdapterWithoutReadingOrRequiringCredentialsOrCallingTheNetwork() {
        context().run(application -> {
            assertNull(application.getStartupFailure());
            assertInstanceOf(KakaoRoadRouteAdapter.class, application.getBean(RoadRouteProvider.class));
            assertEquals(1, application.getBeansOfType(RoadRouteProvider.class).size());
        });
    }

    @Test
    void explicitProviderOverrideRemainsPossibleWithoutDuplicateBeans() {
        RoadRouteProvider override = UnavailableProviders.roadRoute();
        context().withBean(RoadRouteProvider.class, () -> override).run(application -> {
            assertNull(application.getStartupFailure());
            assertSame(override, application.getBean(RoadRouteProvider.class));
            assertEquals(1, application.getBeansOfType(RoadRouteProvider.class).size());
        });
    }
}
