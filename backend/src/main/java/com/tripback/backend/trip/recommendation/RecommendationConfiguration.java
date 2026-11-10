package com.tripback.backend.trip.recommendation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class RecommendationConfiguration {
    @Bean(destroyMethod = "close") @ConditionalOnMissingBean(TripRecommendationModel.class)
    OpenAiTripRecommendationModel tripRecommendationModel(ObjectMapper mapper, Environment environment) {
        return new OpenAiTripRecommendationModel(mapper,
                () -> environment.getProperty("TRIPBACK_ENABLE_AI_RECOMMENDATION_API", Boolean.class, false));
    }
}
