package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 연결 화면의 리전 선택 목록 (GET /aws/regions) */
class RegionListApiTest extends IntegrationTestBase {

    @Test
    void 서울이_기본으로_맨_앞에_오고_목록의_리전으로_연결을_시작할_수_있다() throws Exception {
        JsonNode regions = body(call(get("/api/v1/aws/regions")).andExpect(status().isOk())).at("/regions");

        assertThat(regions.get(0).at("/code").asText()).isEqualTo("ap-northeast-2");
        assertThat(regions.get(0).at("/label").asText()).isEqualTo("서울");
        assertThat(regions.get(0).at("/isDefault").asBoolean()).isTrue();
        assertThat(regions).filteredOn(r -> r.at("/isDefault").asBoolean()).hasSize(1);
        assertThat(regions).noneMatch(r -> r.at("/label").asText().equals(r.at("/code").asText()));

        for (JsonNode region : regions) {
            JsonNode account = body(call(post("/api/v1/aws/accounts").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"region\":\"" + region.at("/code").asText() + "\"}"))
                    .andExpect(status().isCreated())).at("/account");
            assertThat(account.at("/regionLabel").asText()).isEqualTo(region.at("/label").asText());
        }
    }
}
