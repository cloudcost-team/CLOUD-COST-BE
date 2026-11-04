package com.cloudcost.adapter.awscustomer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.application.port.CustomerAwsSession.DescribeResult;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.application.port.CustomerAwsSession.NetworkDirection;
import com.cloudcost.domain.usage.HourlySample;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.sts.model.StsException;

/** 실제 AWS 대신 Mockito로 SDK 클라이언트를 대체한다. */
class AwsCustomerAdapterTest {

    private final Ec2Client ec2 = mock(Ec2Client.class);
    private final CloudWatchClient cloudWatch = mock(CloudWatchClient.class);
    private final AwsCustomerSession session = new AwsCustomerSession(Region.AP_NORTHEAST_2, ec2, cloudWatch);

    @Test
    void DescribeInstances_모든_페이지를_읽고_저장_허용_필드만_꺼낸다() {
        Instance spot = Instance.builder()
                .instanceId("i-0abc")
                .instanceType("t3.micro")
                .state(s -> s.name("running"))
                .launchTime(Instant.parse("2026-10-01T00:00:00Z"))
                .platformDetails("Linux/UNIX")
                .placement(p -> p.availabilityZone("ap-northeast-2a").tenancy("default"))
                .instanceLifecycle("spot")
                .monitoring(m -> m.state("disabled"))
                .privateIpAddress("10.0.0.1")
                .keyName("my-key")
                .tags(t -> t.key("Name").value("dev-api"), t -> t.key("Owner").value("someone"))
                .build();
        Instance plain = Instance.builder().instanceId("i-0def").instanceType("m5.large")
                .state(s -> s.name("stopped")).build();
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(DescribeInstancesResponse.builder()
                        .reservations(Reservation.builder().instances(spot).build())
                        .nextToken("page-2")
                        .build())
                .thenReturn(DescribeInstancesResponse.builder()
                        .reservations(Reservation.builder().instances(plain).build())
                        .build());

        DescribeResult result = session.describeInstances();

        assertThat(result.requestCount()).isEqualTo(2);
        assertThat(result.instances()).hasSize(2);
        Ec2Instance first = result.instances().getFirst();
        assertThat(first).isEqualTo(new Ec2Instance("i-0abc", "dev-api", "t3.micro", "ap-northeast-2",
                "ap-northeast-2a", "running", Instant.parse("2026-10-01T00:00:00Z"), "Linux/UNIX", "default", true, false));
        assertThat(first.toString()).doesNotContain("10.0.0.1").doesNotContain("my-key").doesNotContain("someone");
    }

    @Test
    void GetMetricStatistics는_CPUUtilization_SampleCount_3600초로_요청한다() {
        when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(GetMetricStatisticsResponse.builder()
                        .datapoints(
                                Datapoint.builder().timestamp(Instant.parse("2026-10-07T01:00:00Z")).sampleCount(12.0).build(),
                                Datapoint.builder().timestamp(Instant.parse("2026-10-07T00:00:00Z")).sampleCount(6.0).build())
                        .build());
        Instant start = Instant.parse("2026-10-07T00:00:00Z");
        Instant end = Instant.parse("2026-10-08T00:00:00Z");

        List<HourlySample> samples = session.hourlyCpu("i-0abc", start, end);

        ArgumentCaptor<GetMetricStatisticsRequest> captor = ArgumentCaptor.forClass(GetMetricStatisticsRequest.class);
        org.mockito.Mockito.verify(cloudWatch).getMetricStatistics(captor.capture());
        GetMetricStatisticsRequest request = captor.getValue();
        assertThat(request.namespace()).isEqualTo("AWS/EC2");
        assertThat(request.metricName()).isEqualTo("CPUUtilization");
        assertThat(request.period()).isEqualTo(3600);
        assertThat(request.statistics()).as("사용 시간과 CPU 평균·최대를 한 번에 받는다 (호출 수 그대로)")
                .containsExactly(Statistic.SAMPLE_COUNT, Statistic.AVERAGE, Statistic.MAXIMUM);
        assertThat(request.dimensions().getFirst().value()).isEqualTo("i-0abc");
        assertThat(request.startTime()).isEqualTo(start);
        assertThat(request.endTime()).isEqualTo(end);
        assertThat(samples).extracting(HourlySample::hourStart)
                .containsExactly(Instant.parse("2026-10-07T00:00:00Z"), Instant.parse("2026-10-07T01:00:00Z"));
    }

    @Test
    void CPU_평균과_최대를_사용_시간과_함께_돌려준다() {
        when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(GetMetricStatisticsResponse.builder()
                        .datapoints(Datapoint.builder().timestamp(Instant.parse("2026-10-07T00:00:00Z"))
                                .sampleCount(60.0).average(6.12345).maximum(23.0).build())
                        .build());

        HourlySample sample = session.hourlyCpu("i-0abc", Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)).getFirst();

        assertThat(sample.sampleCount()).isEqualByComparingTo("60");
        assertThat(sample.cpuAvg()).isEqualByComparingTo("6.123");
        assertThat(sample.cpuMax()).isEqualByComparingTo("23");
        assertThat(sample.networkInBytes()).isNull();
    }

    @Test
    void 네트워크는_NetworkIn_NetworkOut을_Sum으로_한_건씩_요청한다() {
        when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(GetMetricStatisticsResponse.builder()
                        .datapoints(Datapoint.builder().timestamp(Instant.parse("2026-10-07T00:00:00Z"))
                                .sum(1536.6).build())
                        .build());

        List<com.cloudcost.domain.usage.HourlyNetwork> in = session.hourlyNetwork(
                "i-0abc", NetworkDirection.IN, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        List<com.cloudcost.domain.usage.HourlyNetwork> out = session.hourlyNetwork(
                "i-0abc", NetworkDirection.OUT, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));

        ArgumentCaptor<GetMetricStatisticsRequest> captor = ArgumentCaptor.forClass(GetMetricStatisticsRequest.class);
        org.mockito.Mockito.verify(cloudWatch, org.mockito.Mockito.times(2)).getMetricStatistics(captor.capture());
        assertThat(captor.getAllValues()).extracting(GetMetricStatisticsRequest::metricName)
                .containsExactly("NetworkIn", "NetworkOut");
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.namespace()).isEqualTo("AWS/EC2");
            assertThat(r.period()).isEqualTo(3600);
            assertThat(r.statistics()).containsExactly(Statistic.SUM);
        });
        assertThat(in.getFirst().inBytes()).isEqualTo(1537L);
        assertThat(in.getFirst().outBytes()).isNull();
        assertThat(out.getFirst().outBytes()).isEqualTo(1537L);
        assertThat(out.getFirst().inBytes()).isNull();
    }

    @Test
    void 권한_부족은_필요한_IAM_액션과_함께_한국어로_알린다() {
        when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenThrow(CloudWatchException.builder().statusCode(403)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied")
                                .errorMessage("User: arn:aws:sts::123456789012:assumed-role/X is not authorized").build())
                        .build());

        assertThatThrownBy(() -> session.hourlyCpu("i-0abc", Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)))
                .isInstanceOfSatisfying(AwsAccessException.class, e -> {
                    assertThat(e.category()).isEqualTo(Category.PERMISSION_DENIED);
                    assertThat(e.getMessage()).startsWith("AccessDenied: cloudwatch:GetMetricStatistics 권한이 없습니다")
                            .doesNotContain("arn:aws:sts");
                });

        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenThrow(Ec2Exception.builder().statusCode(403)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("UnauthorizedOperation").build()).build());
        assertThatThrownBy(session::verifyAccess)
                .isInstanceOfSatisfying(AwsAccessException.class, e ->
                        assertThat(e.getMessage()).startsWith("UnauthorizedOperation: ec2:DescribeInstances 권한이 없습니다"));
    }

    @Test
    void AssumeRole_오류_분류() {
        AwsAccessException denied = CustomerAwsErrors.translate(StsException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build(), "sts:AssumeRole");
        assertThat(denied.category()).isEqualTo(Category.ASSUME_ROLE_DENIED);
        assertThat(denied.getMessage()).contains("External ID");

        AwsAccessException backend = CustomerAwsErrors.translate(StsException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidClientTokenId").build()).build(), "sts:AssumeRole");
        assertThat(backend.category()).isEqualTo(Category.BACKEND_CREDENTIALS_MISSING);

        AwsAccessException noCreds = CustomerAwsErrors.translate(
                SdkClientException.create("Unable to load credentials from any of the providers in the chain"), "sts:AssumeRole");
        assertThat(noCreds.category()).isEqualTo(Category.BACKEND_CREDENTIALS_MISSING);
        assertThat(noCreds.getMessage()).contains("사용자 설정 문제가 아닙니다");

        AwsAccessException network = CustomerAwsErrors.translate(
                SdkClientException.create("Connect timed out"), "ec2:DescribeInstances");
        assertThat(network.category()).isEqualTo(Category.UNAVAILABLE);

        AwsAccessException throttled = CustomerAwsErrors.translate(Ec2Exception.builder().statusCode(503)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("RequestLimitExceeded").build()).build(),
                "ec2:DescribeInstances");
        assertThat(throttled.category()).isEqualTo(Category.THROTTLED);
    }

    @Test
    void 리전_비활성과_요청_제한_오류_분류() {
        AwsAccessException stsRegion = CustomerAwsErrors.translate(StsException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("RegionDisabledException")
                        .errorMessage("STS is not activated in this region for account:123456789012").build()).build(),
                "sts:AssumeRole");
        assertThat(stsRegion.category()).isEqualTo(Category.REGION_DISABLED);
        assertThat(stsRegion.breaksConnection()).isTrue();
        assertThat(stsRegion.getMessage()).contains("리전").doesNotContain("123456789012");

        AwsAccessException optIn = CustomerAwsErrors.translate(Ec2Exception.builder().statusCode(401)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("OptInRequired").build()).build(),
                "ec2:DescribeInstances");
        assertThat(optIn.category()).isEqualTo(Category.REGION_DISABLED);
        assertThat(optIn.getMessage()).startsWith("OptInRequired: 연결한 리전에서 요청이 거부되었습니다");

        AwsAccessException cwThrottle = CustomerAwsErrors.translate(CloudWatchException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("Throttling").build()).build(),
                "cloudwatch:GetMetricStatistics");
        assertThat(cwThrottle.category()).isEqualTo(Category.THROTTLED);
        assertThat(cwThrottle.retryable()).isTrue();
        assertThat(cwThrottle.getMessage()).startsWith("Throttling: AWS 요청이 일시적으로 제한되었습니다");

        AwsAccessException assumeDenied = CustomerAwsErrors.translate(StsException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build(), "sts:AssumeRole");
        assertThat(assumeDenied.getMessage()).as("Role·스택 삭제 가능성도 안내한다").contains("삭제되지 않았는지");
        assertThat(assumeDenied.retryable()).isFalse();
    }
}
