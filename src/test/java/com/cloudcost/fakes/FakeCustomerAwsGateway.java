package com.cloudcost.fakes;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.CustomerAwsGateway;
import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.ExternalId;
import com.cloudcost.domain.account.RoleArn;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.HourlyNetwork;
import com.cloudcost.domain.usage.HourlySample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 실제 AWS 대신 쓰는 가짜 고객 계정. 호출 수를 세어 캐시 동작을 검증한다. */
public class FakeCustomerAwsGateway implements CustomerAwsGateway {

    public final List<Ec2Instance> instances = new ArrayList<>();
    public final Map<String, List<HourlySample>> samples = new HashMap<>();
    /** 인스턴스별 시간당 네트워크 (입력·출력 둘 다 채워 둔다) */
    public final Map<String, List<HourlyNetwork>> network = new HashMap<>();
    public AwsAccessException assumeError;
    /** 앞에서 몇 번은 assumeError로 실패하고 그다음부터 성공 (IAM 반영 지연 재현) */
    public int assumeFailuresBeforeSuccess = -1;
    public AwsAccessException describeError;
    public AwsAccessException cloudWatchError;
    /** NetworkIn·NetworkOut 조회만 실패시킬 오류 */
    public AwsAccessException networkError;
    /** 요청("instanceId@start~end")별로 실패시킬 오류 (null이면 성공). 특정 구간·횟수만 실패시킬 때 쓴다. */
    public Function<String, AwsAccessException> cloudWatchFailure = request -> null;

    public int assumeCalls;
    public int verifyCalls;
    public int describeCalls;
    /** CPUUtilization 조회 수 (사용 시간·CPU) */
    public int cloudWatchCalls;
    /** NetworkIn·NetworkOut 조회 수 */
    public int networkCalls;
    public final List<String> networkRequests = new ArrayList<>();
    public final List<String> cloudWatchRequests = new ArrayList<>();
    public ExternalId lastExternalId;
    public RoleArn lastRoleArn;
    public int openSessions;
    /** 확장 수집 (ADR-020): 켜진 리전과 리전별 리소스 */
    public final List<String> regions = new ArrayList<>();
    public final Map<String, List<com.cloudcost.domain.resource.AwsResource>> regionResources = new HashMap<>();
    public AwsAccessException resourceError;
    public int regionScans;

    public void reset() {
        regions.clear();
        regions.addAll(List.of("ap-northeast-2", "us-east-1"));
        regionResources.clear();
        resourceError = null;
        regionScans = 0;
        instances.clear();
        samples.clear();
        network.clear();
        networkCalls = 0;
        networkRequests.clear();
        assumeError = null;
        assumeFailuresBeforeSuccess = -1;
        describeError = null;
        cloudWatchError = null;
        networkError = null;
        cloudWatchFailure = request -> null;
        assumeCalls = 0;
        verifyCalls = 0;
        describeCalls = 0;
        cloudWatchCalls = 0;
        cloudWatchRequests.clear();
        lastExternalId = null;
        lastRoleArn = null;
        openSessions = 0;
    }

    public void addHours(String instanceId, Instant firstHour, int hours, double sampleCount) {
        List<HourlySample> list = samples.computeIfAbsent(instanceId, k -> new ArrayList<>());
        for (int h = 0; h < hours; h++) {
            list.add(new HourlySample(instanceId, firstHour.plusSeconds(3600L * h), java.math.BigDecimal.valueOf(sampleCount)));
        }
    }

    /** CPU 평균·최대가 있는 시간별 샘플 (켜져 있던 시간) */
    public void addCpuHours(String instanceId, Instant firstHour, int hours, double cpuAvg, double cpuMax) {
        List<HourlySample> list = samples.computeIfAbsent(instanceId, k -> new ArrayList<>());
        for (int h = 0; h < hours; h++) {
            list.add(HourlySample.cpu(instanceId, firstHour.plusSeconds(3600L * h), java.math.BigDecimal.valueOf(60),
                    java.math.BigDecimal.valueOf(cpuAvg), java.math.BigDecimal.valueOf(cpuMax)));
        }
    }

    public void addNetworkHours(String instanceId, Instant firstHour, int hours, long inBytes, long outBytes) {
        List<HourlyNetwork> list = network.computeIfAbsent(instanceId, k -> new ArrayList<>());
        for (int h = 0; h < hours; h++) {
            list.add(new HourlyNetwork(instanceId, firstHour.plusSeconds(3600L * h), inBytes, outBytes));
        }
    }

    @Override
    public CustomerAwsSession open(RoleArn roleArn, ExternalId externalId, AwsRegion region, String sessionName) {
        assumeCalls++;
        lastExternalId = externalId;
        lastRoleArn = roleArn;
        if (assumeError != null) {
            if (assumeFailuresBeforeSuccess < 0) {
                throw assumeError;
            }
            if (assumeFailuresBeforeSuccess > 0) {
                assumeFailuresBeforeSuccess--;
                throw assumeError;
            }
        }
        openSessions++;
        return new CustomerAwsSession() {
            @Override
            public void verifyAccess() {
                verifyCalls++;
                if (describeError != null) {
                    throw describeError;
                }
            }

            @Override
            public DescribeResult describeInstances() {
                describeCalls++;
                if (describeError != null) {
                    throw describeError;
                }
                return new DescribeResult(List.copyOf(instances), 1);
            }

            @Override
            public List<HourlySample> hourlyCpu(String instanceId, Instant start, Instant end) {
                cloudWatchCalls++;
                String request = instanceId + "@" + start + "~" + end;
                cloudWatchRequests.add(request);
                if (cloudWatchError != null) {
                    throw cloudWatchError;
                }
                AwsAccessException failure = cloudWatchFailure.apply(request);
                if (failure != null) {
                    throw failure;
                }
                return samples.getOrDefault(instanceId, List.of()).stream()
                        .filter(s -> !s.hourStart().isBefore(start) && s.hourStart().isBefore(end))
                        .toList();
            }

            @Override
            public List<HourlyNetwork> hourlyNetwork(
                    String instanceId, NetworkDirection direction, Instant start, Instant end) {
                networkCalls++;
                networkRequests.add(direction + ":" + instanceId + "@" + start + "~" + end);
                if (cloudWatchError != null) {
                    throw cloudWatchError;
                }
                if (networkError != null) {
                    throw networkError;
                }
                return network.getOrDefault(instanceId, List.of()).stream()
                        .filter(n -> !n.hourStart().isBefore(start) && n.hourStart().isBefore(end))
                        .map(n -> direction == NetworkDirection.IN
                                ? new HourlyNetwork(instanceId, n.hourStart(), n.inBytes(), null)
                                : new HourlyNetwork(instanceId, n.hourStart(), null, n.outBytes()))
                        .toList();
            }

            @Override
            public List<String> enabledRegions() {
                if (resourceError != null) {
                    throw resourceError;
                }
                return List.copyOf(regions);
            }

            @Override
            public RegionScan scanRegion(String region, boolean withInstances, Instant now) {
                synchronized (FakeCustomerAwsGateway.this) {
                    regionScans++;
                }
                if (resourceError != null) {
                    throw resourceError;
                }
                List<com.cloudcost.domain.resource.AwsResource> found = regionResources.getOrDefault(region, List.of())
                        .stream()
                        .filter(r -> withInstances || r.type() != com.cloudcost.domain.resource.ResourceType.INSTANCE)
                        .toList();
                return new RegionScan(found, 5, 0);
            }

            @Override
            public void close() {
                openSessions--;
            }
        };
    }
}
