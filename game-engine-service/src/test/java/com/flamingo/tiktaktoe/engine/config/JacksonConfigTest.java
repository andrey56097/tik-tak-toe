package com.flamingo.tiktaktoe.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();

    @Test
    void objectMapperBeanIsUsable() throws Exception {
        assertThat(objectMapper).isNotNull();

        String json = objectMapper.writeValueAsString(new int[] {1, 2, 3});
        int[] roundTripped = objectMapper.readValue(json, int[].class);

        assertThat(roundTripped).containsExactly(1, 2, 3);
    }
}
