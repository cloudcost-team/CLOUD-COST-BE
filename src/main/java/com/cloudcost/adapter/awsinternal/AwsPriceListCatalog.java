package com.cloudcost.adapter.awsinternal;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.application.port.PriceCatalog;
import com.cloudcost.domain.cost.PriceQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

/**
 * 우리 계정(백엔드 자격증명)으로 AWS Price List를 조회한다. 고객 계정 권한을 쓰지 않는다. 필요한 백엔드 권한: pricing:GetProducts. Price List API 엔드포인트는
 * us-east-1을 쓴다.
 */
@Component
class AwsPriceListCatalog implements PriceCatalog, AutoCloseable {

    private static final String ACTION = "pricing:GetProducts";

    private final AwsCredentialsProvider backendCredentials;
    private final Region pricingRegion;
    private final ObjectMapper mapper;
    private final ClientOverrideConfiguration clientConfig = ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(20))
        .build();

    AwsPriceListCatalog(@Value("${cci.aws.pricing-region:us-east-1}") String pricingRegion, ObjectMapper mapper) {
        this.backendCredentials = DefaultCredentialsProvider.builder().build();
        this.pricingRegion = Region.of(pricingRegion);
        this.mapper = mapper;
    }

    @Override
    public Optional<BigDecimal> findOnDemandHourlyUsd(PriceQuery q) {
        GetProductsRequest request = GetProductsRequest.builder()
            .serviceCode("AmazonEC2")
            .formatVersion("aws_v1")
            .maxResults(10)
            .filters(List.of(
                term("regionCode", q.region()),
                term("instanceType", q.instanceType()),
                term("operatingSystem", q.operatingSystem()),
                term("tenancy", q.tenancy()),
                term("licenseModel", q.licenseModel()),
                term("preInstalledSw", q.preInstalledSw()),
                term("capacitystatus", "Used")))
            .build();
        try (PricingClient pricing = PricingClient.builder()
            .region(pricingRegion)
            .credentialsProvider(backendCredentials)
            .overrideConfiguration(clientConfig)
            .build()) {
            GetProductsResponse response = pricing.getProducts(request);
            return PriceListParser.onDemandHourlyUsd(response.priceList(), mapper);
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? "Unknown" : e.awsErrorDetails().errorCode();
            Category category = e.statusCode() == 403 || "AccessDeniedException".equals(code)
                ? Category.PERMISSION_DENIED
                : e.isThrottlingException() ? Category.THROTTLED : Category.UNKNOWN;
            String message = category == Category.PERMISSION_DENIED
                ? "AccessDenied: 백엔드 Role에 " + ACTION + " 권한이 없습니다 (관리자 설정 필요)."
                : code + ": 단가 조회에 실패했습니다.";
            throw new AwsAccessException(category, code, ACTION, message);
        } catch (SdkClientException e) {
            throw new AwsAccessException(Category.BACKEND_CREDENTIALS_MISSING, "ClientError", ACTION,
                "서버의 AWS 자격증명이 없거나 Price List에 연결하지 못했습니다 (관리자 설정 필요).");
        }
    }

    private static Filter term(String field, String value) {
        return Filter.builder().type(FilterType.TERM_MATCH).field(field).value(value).build();
    }

    @Override
    public void close() {
        if (backendCredentials instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 종료 중
            }
        }
    }
}
