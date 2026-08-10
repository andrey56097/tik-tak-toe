package com.flamingo.tiktaktoe.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4.1's Jackson auto-configuration only registers a Jackson 3
 * {@code tools.jackson.databind.json.JsonMapper} bean, not a Jackson 2
 * {@link ObjectMapper}. {@link com.flamingo.tiktaktoe.engine.mapper.GameMapper}
 * depends on the Jackson 2 API, so that bean must be provided explicitly.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
