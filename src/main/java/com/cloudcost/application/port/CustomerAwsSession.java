package com.cloudcost.application.port;

import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.HourlyNetwork;
import com.cloudcost.domain.usage.HourlySample;
import java.time.Instant;
import java.util.List;

/**
 * AssumeRole로 얻은 고객 계정 세션. 허용된 두 API만 노출한다.
 * <ul>
 *   <li>ec2:DescribeInstances
 *   <li>cloudwatch:GetMetricStatistics
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
