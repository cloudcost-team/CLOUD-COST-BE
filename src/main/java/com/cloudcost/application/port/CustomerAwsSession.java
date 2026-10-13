package com.cloudcost.application.port;

import com.cloudcost.domain.ec2.Ec2Instance;
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
     * GetMetricStatistics 1건 (AWS/EC2 CPUUtilization, Period 3600, SampleCount).
     */
    List<HourlySample> hourlySampleCounts(String instanceId, Instant start, Instant end);

    @Override
    void close();

    record DescribeResult(List<Ec2Instance> instances, int requestCount) {
    }
}
