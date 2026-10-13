package com.cloudcost.fakes;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.CustomerAwsGateway;
import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.ExternalId;
import com.cloudcost.domain.account.RoleArn;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.HourlySample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 실제 AWS 대신 쓰는 가짜 고객 계정. 호출 수를 세어 캐시 동작을 검증한다. */
public class FakeCustomerAwsGateway implements CustomerAwsGateway {

    public final List<Ec2Instance> instances = new ArrayList<>();
    public final Map<String, List<HourlySample>> samples = new HashMap<>();
    public AwsAccessException assumeError;
    /** 앞에서 몇 번은 assumeError로 실패하고 그다음부터 성공 (IAM 반영 지연 재현) */
    public int assumeFailuresBeforeSuccess = -1;
    public AwsAccessException describeError;
    public AwsAccessException cloudWatchError;

    public int assumeCalls;
    public int verifyCalls;
    public int describeCalls;
    public int cloudWatchCalls;
    public final List<String> cloudWatchRequests = new ArrayList<>();
    public ExternalId lastExternalId;
    public RoleArn lastRoleArn;
    public int openSessions;

    public void reset() {
        instances.clear();
        samples.clear();
        assumeError = null;
        assumeFailuresBeforeSuccess = -1;
        describeError = null;
        cloudWatchError = null;
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
            public List<HourlySample> hourlySampleCounts(String instanceId, Instant start, Instant end) {
                cloudWatchCalls++;
                cloudWatchRequests.add(instanceId + "@" + start + "~" + end);
                if (cloudWatchError != null) {
                    throw cloudWatchError;
                }
                return samples.getOrDefault(instanceId, List.of()).stream()
                        .filter(s -> !s.hourStart().isBefore(start) && s.hourStart().isBefore(end))
                        .toList();
            }

            @Override
            public void close() {
                openSessions--;
            }
        };
    }
}
