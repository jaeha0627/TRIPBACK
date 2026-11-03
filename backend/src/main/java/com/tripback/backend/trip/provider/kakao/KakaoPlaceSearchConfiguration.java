package com.tripback.backend.trip.provider.kakao;

import com.tripback.backend.trip.provider.PlaceSearchProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class KakaoPlaceSearchConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(PlaceSearchProvider.class)
    KakaoPlaceSearchAdapter kakaoPlaceSearchProvider(ObjectMapper mapper) {
        // Bean creation does not require a key and never makes an HTTP call.
        return new KakaoPlaceSearchAdapter(mapper);
    }
}
