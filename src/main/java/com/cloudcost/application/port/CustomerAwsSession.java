package com.cloudcost.application.port;

import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.usage.HourlyNetwork;
import com.cloudcost.domain.usage.HourlySample;
import java.time.Instant;
import java.util.List;

/**
 * AssumeRole로 얻은 고객 계정 세션. 허용된 읽기 API만 노출한다 (scripts/customer-role-allowlist.txt).
 * <ul>
 *   <li>ec2:DescribeInstances, cloudwatch:GetMetricStatistics — 연결한 리전의 사용량
 *   <li>ec2:DescribeRegions, ec2:DescribeVolumes, ec2:DescribeAddresses, ec2:DescribeSnapshots(OwnerIds=self),
 *       elasticloadbalancing:DescribeLoadBalancers — 방치 리소스·잊힌 리전 (ADR-020)
 * </ul>
 */
public interface CustomerAwsSession extends AutoCloseable {

    /**
     * 연결 검증: DescribeInstances 1건만 호출한다.
     */
    void verifyAccess();

    /**
     * 모든 페이지를 읽는다. requestCount = 실제 호출한 DescribeInstances 수.
     */
    DescribeResult describeInstances();

    /**
     * GetMetricStatistics 1건 (AWS/EC2 CPUUtilization, Period 3600, SampleCount·Average·Maximum).
     * 사용 시간(SampleCount)과 CPU 사용률(평균·최대)을 한 번에 받는다.
     */
    List<HourlySample> hourlyCpu(String instanceId, Instant start, Instant end);

    /**
     * GetMetricStatistics 1건 (AWS/EC2 NetworkIn 또는 NetworkOut, Period 3600, Sum).
     * 돌려주는 값은 요청한 방향만 채워져 있고 다른 방향은 null이다.
     */
    List<HourlyNetwork> hourlyNetwork(String instanceId, NetworkDirection direction, Instant start, Instant end);

    /** ec2:DescribeRegions 1건. 이 계정에서 켜져 있는 리전 이름 */
    List<String> enabledRegions();

    /**
     * 리전 1곳의 방치 리소스 후보를 읽는다 (ADR-020). 볼륨·고정 IP·스냅샷(내 것만)·로드밸런서를 모두 읽고,
     * withInstances면 그 리전의 서버도 읽는다 (기본 리전 밖의 잊힌 서버). 로드밸런서마다 최근 14일 요청 수를
     * CloudWatch(GetMetricStatistics, Period 1일)로 읽는다 (ALB는 요청 0이면 거부 요청 지표 1건 더).
     */
    RegionScan scanRegion(String region, boolean withInstances, Instant now);

    /**
     * @param describeCount   부른 Describe 수
     * @param cloudWatchCount 부른 GetMetricStatistics 수
     */
    record RegionScan(List<AwsResource> resources, int describeCount, int cloudWatchCount) {}

    /** 이 세션에서 확장 수집(enabledRegions·scanRegion)으로 시도한 호출 수. 실패한 호출도 센다 */
    ScanCalls scanCalls();

    record ScanCalls(int describeCount, int cloudWatchCount) {}

    enum NetworkDirection {
        IN("NetworkIn"),
        OUT("NetworkOut");

        private final String metricName;

        NetworkDirection(String metricName) {
            this.metricName = metricName;
        }

        public String metricName() {
            return metricName;
        }
    }

    @Override
    void close();

    record DescribeResult(List<Ec2Instance> instances, int requestCount) {
    }
}
