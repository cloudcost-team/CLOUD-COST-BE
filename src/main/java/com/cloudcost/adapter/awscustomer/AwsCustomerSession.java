package com.cloudcost.adapter.awscustomer;

import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.HourlySample;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;

/**
 * AssumeRole로 받은 임시 자격증명을 쓰는 고객 계정 세션.
 *
 * <p>임시 자격증명은 {@code credentials}(StsAssumeRoleCredentialsProvider) 안에만 있고, 이 클래스는 꺼내지 않는다.
 * 호출하는 API는 ec2:DescribeInstances, cloudwatch:GetMetricStatistics 두 가지뿐이다. 연결 1회(조회 1회)에서만 쓰고 close()로 클라이언트를 모두 닫는다.
 */
final class AwsCustomerSession implements CustomerAwsSession {

    static final String DESCRIBE_INSTANCES = "ec2:DescribeInstances";
    static final String GET_METRIC_STATISTICS = "cloudwatch:GetMetricStatistics";

    private final Region region;
    private final AwsCredentialsProvider credentials;
    private final ClientOverrideConfiguration clientConfig;
    private final List<SdkAutoCloseable> owned = new ArrayList<>();
    private Ec2Client ec2;
    private CloudWatchClient cloudWatch;

    AwsCustomerSession(
        Region region,
        AwsCredentialsProvider credentials,
        ClientOverrideConfiguration clientConfig,
        List<SdkAutoCloseable> ownedResources) {
        this.region = region;
        this.credentials = credentials;
        this.clientConfig = clientConfig;
        this.owned.addAll(ownedResources);
    }

    /**
     * 테스트용: 이미 만든 클라이언트를 주입한다.
     */
    AwsCustomerSession(Region region, Ec2Client ec2, CloudWatchClient cloudWatch) {
        this(region, null, null, List.of());
        this.ec2 = ec2;
        this.cloudWatch = cloudWatch;
    }

    @Override
    public void verifyAccess() {
        try {
            ec2().describeInstances(DescribeInstancesRequest.builder().maxResults(5).build());
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_INSTANCES);
        }
    }

    @Override
    public DescribeResult describeInstances() {
        List<Ec2Instance> result = new ArrayList<>();
        int requests = 0;
        String token = null;
        try {
            do {
                DescribeInstancesResponse page = ec2().describeInstances(
                    DescribeInstancesRequest.builder().maxResults(1000).nextToken(token).build());
                requests++;
                page.reservations().forEach(r -> r.instances()
                    .forEach(i -> result.add(Ec2InstanceMapper.toDomain(i, region.id()))));
                token = page.nextToken();
            } while (token != null && !token.isEmpty());
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_INSTANCES);
        }
        return new DescribeResult(result, requests);
    }

    @Override
    public List<HourlySample> hourlySampleCounts(String instanceId, Instant start, Instant end) {
        GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
            .namespace("AWS/EC2")
            .metricName("CPUUtilization")
            .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
            .startTime(start)
            .endTime(end)
            .period(3600)
            .statistics(Statistic.SAMPLE_COUNT)
            .build();
        GetMetricStatisticsResponse response;
        try {
            response = cloudWatch().getMetricStatistics(request);
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, GET_METRIC_STATISTICS);
        }
        return response.datapoints().stream()
            .filter(d -> d.timestamp() != null && d.sampleCount() != null && d.sampleCount() > 0)
            .sorted(Comparator.comparing(Datapoint::timestamp))
            .map(d -> new HourlySample(instanceId, d.timestamp(), BigDecimal.valueOf(d.sampleCount())))
            .toList();
    }

    private Ec2Client ec2() {
        if (ec2 == null) {
            ec2 = Ec2Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .overrideConfiguration(clientConfig)
                .build();
            owned.add(0, ec2);
        }
        return ec2;
    }

    private CloudWatchClient cloudWatch() {
        if (cloudWatch == null) {
            cloudWatch = CloudWatchClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .overrideConfiguration(clientConfig)
                .build();
            owned.add(0, cloudWatch);
        }
        return cloudWatch;
    }

    @Override
    public void close() {
        for (SdkAutoCloseable resource : owned) {
            try {
                resource.close();
            } catch (RuntimeException ignored) {
                // 닫기 실패는 응답에 영향을 주지 않는다.
            }
        }
        owned.clear();
    }

    @Override
    public String toString() {
        return "AwsCustomerSession(region=" + region.id() + ")";
    }
}
