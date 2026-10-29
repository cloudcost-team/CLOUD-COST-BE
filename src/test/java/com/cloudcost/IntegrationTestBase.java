package com.cloudcost;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.fakes.FakeCustomerAwsGateway;
import com.cloudcost.fakes.FakeExchangeRateSource;
import com.cloudcost.fakes.FakeGoogleIdentityVerifier;
import com.cloudcost.fakes.FakeStackCallbackResponder;
import com.cloudcost.fakes.FakePriceCatalog;
import com.cloudcost.fakes.Fixtures;
import com.cloudcost.fakes.MutableClock;
import com.cloudcost.fakes.RecordingPauser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * H2 DB + 가짜 AWS로 전체 흐름(API → application → domain → persistence)을 검증한다. 실제 AWS를 부르지 않는다.
 * 테스트마다 owner id를 새로 만들어 서로 데이터가 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestBase.FakeAwsConfig.class)
public abstract class IntegrationTestBase {

    /** 2026-10-08 12:00 KST */
    protected static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected FakeCustomerAwsGateway aws;
    @Autowired protected FakePriceCatalog priceCatalog;
    @Autowired protected MutableClock clock;
    @Autowired protected FakeStackCallbackResponder cfnResponses;
    @Autowired protected FakeExchangeRateSource exchangeRates;
    @Autowired protected RecordingPauser pauser;

    protected String owner;

    @TestConfiguration
    static class FakeAwsConfig {
        @Bean
        @Primary
        FakeCustomerAwsGateway fakeCustomerAwsGateway() {
            return new FakeCustomerAwsGateway();
        }

        @Bean
        @Primary
        FakePriceCatalog fakePriceCatalog() {
            return new FakePriceCatalog();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        FakeGoogleIdentityVerifier fakeGoogleIdentityVerifier() {
            return new FakeGoogleIdentityVerifier();
        }

        @Bean
        @Primary
        FakeExchangeRateSource fakeExchangeRateSource() {
            return new FakeExchangeRateSource();
        }

        @Bean
        @Primary
        FakeStackCallbackResponder fakeStackCallbackResponder() {
            return new FakeStackCallbackResponder();
        }

        @Bean
        @Primary
        RecordingPauser recordingPauser() {
            return new RecordingPauser();
        }
    }

    @BeforeEach
    void resetFakes() {
        aws.reset();
        priceCatalog.reset();
        cfnResponses.responses.clear();
        exchangeRates.reset();
        pauser.pauses.clear();
        clock.set(NOW);
        owner = "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected MockHttpServletRequestBuilder as(String ownerId, MockHttpServletRequestBuilder request) {
        return request.header("X-Owner-Id", ownerId);
    }

    protected ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(as(owner, request));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected String createAccount() throws Exception {
        ResultActions result = call(post("/api/v1/aws/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"region\":\"ap-northeast-2\"}"))
                .andExpect(status().isCreated());
        return body(result).at("/account/accountId").asText();
    }

    protected ResultActions registerRole(String accountId, String roleArn) throws Exception {
        return call(put("/api/v1/aws/accounts/" + accountId + "/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleArn\":\"" + roleArn + "\"}"));
    }

    protected String activeAccount() throws Exception {
        String id = createAccount();
        registerRole(id, Fixtures.CUSTOMER_ROLE_ARN).andExpect(status().isOk());
        aws.assumeCalls = 0;
        aws.verifyCalls = 0;
        return id;
    }

    protected ResultActions getJson(String path) throws Exception {
        return call(get(path));
    }
}
