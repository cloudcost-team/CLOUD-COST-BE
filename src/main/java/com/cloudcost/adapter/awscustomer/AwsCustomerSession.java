package com.cloudcost.adapter.awscustomer;

import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceType;
import com.cloudcost.domain.usage.HourlyNetwork;
import com.cloudcost.domain.usage.HourlySample;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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
import software.amazon.awssdk.services.ec2.model.DescribeAddressesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.VolumeState;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;

/**
 * AssumeRole로 받은 임시 자격증명을 쓰는 고객 계정 세션.
 *
 * <p>임시 자격증명은 {@code credentials}(StsAssumeRoleCredentialsProvider) 안에만 있고, 이 클래스는 꺼내지 않는다.
 * 호출하는 API는 허용 목록(scripts/customer-role-allowlist.txt)의 읽기 API뿐이다. 연결 1회(조회 1회)에서만 쓰고 close()로 클라이언트를 모두 닫는다.
 * 다른 리전 조회({@link #scanRegion})는 그 리전 클라이언트를 호출 안에서 만들고 바로 닫는다 (여러 리전을 동시에 읽어도 안전하다).
 */
final class AwsCustomerSession implements CustomerAwsSession {

    static final String DESCRIBE_INSTANCES = "ec2:DescribeInstances";
    static final String GET_METRIC_STATISTICS = "cloudwatch:GetMetricStatistics";
    static final String DESCRIBE_REGIONS = "ec2:DescribeRegions";
    static final String DESCRIBE_VOLUMES = "ec2:DescribeVolumes";
    static final String DESCRIBE_ADDRESSES = "ec2:DescribeAddresses";
    static final String DESCRIBE_SNAPSHOTS = "ec2:DescribeSnapshots";
    static final String DESCRIBE_LOAD_BALANCERS = "elasticloadbalancing:DescribeLoadBalancers";
    /** LB 요청 수를 읽는 기간 (ADR-020 IDLE_LB) */
    static final Duration LB_WINDOW = Duration.ofDays(14);

    private final Region region;
    private final AwsCredentialsProvider credentials;
    private final ClientOverrideConfiguration clientConfig;
    private final List<SdkAutoCloseable> owned = new ArrayList<>();
    private Ec2Client ec2;
    private CloudWatchClient cloudWatch;
    /** 확장 수집(enabledRegions·scanRegion)에서 시도한 호출 수. 실패한 호출도 센다 (여러 리전 스레드가 함께 더한다) */
    private final AtomicInteger scanDescribeCalls = new AtomicInteger();
    private final AtomicInteger scanCloudWatchCalls = new AtomicInteger();

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
    public List<HourlySample> hourlyCpu(String instanceId, Instant start, Instant end) {
        GetMetricStatisticsResponse response = getMetricStatistics(
            "CPUUtilization", instanceId, start, end, Statistic.SAMPLE_COUNT, Statistic.AVERAGE, Statistic.MAXIMUM);
        return response.datapoints().stream()
            .filter(d -> d.timestamp() != null && d.sampleCount() != null && d.sampleCount() > 0)
            .sorted(Comparator.comparing(Datapoint::timestamp))
            .map(d -> HourlySample.cpu(instanceId, d.timestamp(), BigDecimal.valueOf(d.sampleCount()),
                percent(d.average()), percent(d.maximum())))
            .toList();
    }

    @Override
    public List<HourlyNetwork> hourlyNetwork(
        String instanceId, NetworkDirection direction, Instant start, Instant end) {
        GetMetricStatisticsResponse response =
            getMetricStatistics(direction.metricName(), instanceId, start, end, Statistic.SUM);
        return response.datapoints().stream()
            .filter(d -> d.timestamp() != null && d.sum() != null)
            .sorted(Comparator.comparing(Datapoint::timestamp))
            .map(d -> {
                long bytes = Math.round(d.sum());
                return direction == NetworkDirection.IN
                    ? new HourlyNetwork(instanceId, d.timestamp(), bytes, null)
                    : new HourlyNetwork(instanceId, d.timestamp(), null, bytes);
            })
            .toList();
    }

    private GetMetricStatisticsResponse getMetricStatistics(
        String metricName, String instanceId, Instant start, Instant end, Statistic... statistics) {
        GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
            .namespace("AWS/EC2")
            .metricName(metricName)
            .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
            .startTime(start)
            .endTime(end)
            .period(3600)
            .statistics(statistics)
            .build();
        try {
            return cloudWatch().getMetricStatistics(request);
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, GET_METRIC_STATISTICS);
        }
    }

    @Override
    public List<String> enabledRegions() {
        scanDescribeCalls.incrementAndGet();
        try {
            return ec2().describeRegions(DescribeRegionsRequest.builder().build()).regions().stream()
                .map(r -> r.regionName())
                .sorted()
                .toList();
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_REGIONS);
        }
    }

    @Override
    public RegionScan scanRegion(String regionName, boolean withInstances, Instant now) {
        Region target = Region.of(regionName);
        AtomicInteger describes = new AtomicInteger();
        AtomicInteger metrics = new AtomicInteger();
        List<AwsResource> found = new ArrayList<>();
        try {
            scanRegion(target, regionName, withInstances, now, found, describes, metrics);
        } finally {
            scanDescribeCalls.addAndGet(describes.get());
            scanCloudWatchCalls.addAndGet(metrics.get());
        }
        return new RegionScan(List.copyOf(found), describes.get(), metrics.get());
    }

    @Override
    public ScanCalls scanCalls() {
        return new ScanCalls(scanDescribeCalls.get(), scanCloudWatchCalls.get());
    }

    private void scanRegion(Region target, String regionName, boolean withInstances, Instant now,
                            List<AwsResource> found, AtomicInteger describes, AtomicInteger metrics) {
        // 리전 클라이언트를 닫을 때 공유 자격증명 공급자(StsAssumeRoleCredentialsProvider)까지 닫히지 않도록
        // AutoCloseable이 아닌 위임 공급자로 넘긴다. 공급자는 세션 close()에서 한 번만 닫힌다.
        AwsCredentialsProvider shared = credentials::resolveCredentials;
        try (Ec2Client regionEc2 = Ec2Client.builder().region(target).credentialsProvider(shared)
                .overrideConfiguration(clientConfig).build();
             ElasticLoadBalancingV2Client elb = ElasticLoadBalancingV2Client.builder().region(target)
                 .credentialsProvider(shared).overrideConfiguration(clientConfig).build();
             CloudWatchClient regionCloudWatch = CloudWatchClient.builder().region(target)
                 .credentialsProvider(shared).overrideConfiguration(clientConfig).build()) {
            found.addAll(volumes(regionEc2, regionName, describes));
            found.addAll(addresses(regionEc2, regionName, describes));
            found.addAll(snapshots(regionEc2, regionName, describes));
            if (withInstances) {
                found.addAll(instances(regionEc2, regionName, describes));
            }
            for (LoadBalancer lb : loadBalancers(elb, describes)) {
                Long requests = lbRequests(regionCloudWatch, lb, now, metrics);
                found.add(new AwsResource(ResourceType.LOAD_BALANCER, lb.loadBalancerName(), lb.loadBalancerName(),
                    regionName, lb.typeAsString(), null, true, lb.createdTime(), requests, null));
            }
        }
    }

    private static List<AwsResource> volumes(Ec2Client client, String region, AtomicInteger describes) {
        List<AwsResource> result = new ArrayList<>();
        String token = null;
        try {
            do {
                describes.incrementAndGet();
                DescribeVolumesResponse page = client.describeVolumes(
                    DescribeVolumesRequest.builder().maxResults(500).nextToken(token).build());
                page.volumes().forEach(v -> result.add(new AwsResource(ResourceType.VOLUME, v.volumeId(),
                    nameTag(v.tags()), region, v.volumeTypeAsString(), v.size(), v.state() != VolumeState.AVAILABLE,
                    v.createTime(), null, null)));
                token = page.nextToken();
            } while (token != null && !token.isEmpty());
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_VOLUMES);
        }
        return result;
    }

    /** 공인 IP 주소는 읽기만 하고 저장하지 않는다 (allocationId로만 가리킨다) */
    private static List<AwsResource> addresses(Ec2Client client, String region, AtomicInteger describes) {
        try {
            describes.incrementAndGet();
            var response = client.describeAddresses(DescribeAddressesRequest.builder().build());
            return response.addresses().stream()
                .filter(a -> a.allocationId() != null)
                .map(a -> new AwsResource(ResourceType.ADDRESS, a.allocationId(), nameTag(a.tags()), region, null,
                    null, a.associationId() != null || a.networkInterfaceId() != null, null, null, null))
                .toList();
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_ADDRESSES);
        }
    }

    /** 내 계정이 만든 스냅샷만 (OwnerIds=self). 빼면 공개 스냅샷 수십만 건을 읽게 된다 */
    private static List<AwsResource> snapshots(Ec2Client client, String region, AtomicInteger describes) {
        List<AwsResource> result = new ArrayList<>();
        String token = null;
        try {
            do {
                describes.incrementAndGet();
                DescribeSnapshotsResponse page = client.describeSnapshots(DescribeSnapshotsRequest.builder()
                    .ownerIds("self").maxResults(1000).nextToken(token).build());
                page.snapshots().forEach(s -> result.add(new AwsResource(ResourceType.SNAPSHOT, s.snapshotId(),
                    nameTag(s.tags()), region, null, s.volumeSize(), true, s.startTime(), null, null)));
                token = page.nextToken();
            } while (token != null && !token.isEmpty());
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_SNAPSHOTS);
        }
        return result;
    }

    private static List<AwsResource> instances(Ec2Client client, String region, AtomicInteger describes) {
        List<AwsResource> result = new ArrayList<>();
        String token = null;
        try {
            do {
                describes.incrementAndGet();
                DescribeInstancesResponse page = client.describeInstances(
                    DescribeInstancesRequest.builder().maxResults(1000).nextToken(token).build());
                page.reservations().forEach(r -> r.instances().forEach(i -> {
                    Ec2Instance info = Ec2InstanceMapper.toDomain(i, region);
                    result.add(new AwsResource(ResourceType.INSTANCE, info.instanceId(), info.name(), region,
                        info.instanceType(), null, info.isRunning(), info.launchTime(), null, info.platformDetails()));
                }));
                token = page.nextToken();
            } while (token != null && !token.isEmpty());
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_INSTANCES);
        }
        return result;
    }

    private static List<LoadBalancer> loadBalancers(ElasticLoadBalancingV2Client client, AtomicInteger describes) {
        List<LoadBalancer> result = new ArrayList<>();
        String marker = null;
        try {
            do {
                describes.incrementAndGet();
                DescribeLoadBalancersResponse page = client.describeLoadBalancers(
                    DescribeLoadBalancersRequest.builder().pageSize(400).marker(marker).build());
                result.addAll(page.loadBalancers());
                marker = page.nextMarker();
            } while (marker != null && !marker.isEmpty());
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, DESCRIBE_LOAD_BALANCERS);
        }
        return result;
    }

    /**
     * 최근 14일(지금까지) 요청 수. ALB는 RequestCount, NLB는 NewFlowCount (GetMetricStatistics, Period 1일, Sum).
     * ALB RequestCount는 대상을 고르지 못해 거부한 요청(대상이 없을 때의 503 등)을 세지 않으므로, 0이면
     * HTTPCode_ELB_5XX_Count를 1건 더 읽어 더한다 (거부한 요청이 있으면 쓰이는 LB다).
     * 그 밖의 종류(게이트웨이 LB)는 읽지 않고 null.
     */
    private static Long lbRequests(CloudWatchClient client, LoadBalancer lb, Instant now, AtomicInteger calls) {
        String arn = lb.loadBalancerArn();
        int idx = arn == null ? -1 : arn.indexOf(":loadbalancer/");
        if (idx < 0) {
            return null;
        }
        String dimension = arn.substring(idx + ":loadbalancer/".length());
        if ("application".equals(lb.typeAsString())) {
            long requests = lbMetricSum(client, "AWS/ApplicationELB", "RequestCount", dimension, now, calls);
            return requests > 0 ? requests
                : lbMetricSum(client, "AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", dimension, now, calls);
        }
        if ("network".equals(lb.typeAsString())) {
            return lbMetricSum(client, "AWS/NetworkELB", "NewFlowCount", dimension, now, calls);
        }
        return null;
    }

    /** 지표가 없으면(트래픽이 없던 LB) 0. 조회 구간은 지금 시각까지라 오늘 요청도 들어간다 */
    private static long lbMetricSum(
        CloudWatchClient client, String namespace, String metric, String dimension, Instant now, AtomicInteger calls) {
        Instant end = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        calls.incrementAndGet();
        try {
            GetMetricStatisticsResponse response = client.getMetricStatistics(GetMetricStatisticsRequest.builder()
                .namespace(namespace)
                .metricName(metric)
                .dimensions(Dimension.builder().name("LoadBalancer").value(dimension).build())
                .startTime(end.minus(LB_WINDOW))
                .endTime(end)
                .period(86400)
                .statistics(Statistic.SUM)
                .build());
            return response.datapoints().stream()
                .map(Datapoint::sum)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Math::round)
                .sum();
        } catch (RuntimeException e) {
            throw CustomerAwsErrors.translate(e, GET_METRIC_STATISTICS);
        }
    }

    private static String nameTag(List<Tag> tags) {
        if (tags == null) {
            return null;
        }
        return tags.stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(null);
    }

    /** CPU 사용률(%)은 소수 셋째 자리까지 저장한다. */
    private static BigDecimal percent(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
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
