package com.cloudcost.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class CorsApiTest extends IntegrationTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "https://www.cloud-cost.cloud"})
    void 등록된_프론트_출처의_사전요청을_허용한다(String origin) throws Exception {
        mvc.perform(options("/api/v1/dashboard")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void 등록되지_않은_출처는_거부한다() throws Exception {
        mvc.perform(options("/api/v1/dashboard")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
