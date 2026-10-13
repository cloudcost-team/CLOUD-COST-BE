package com.cloudcost.domain.usage;

import com.cloudcost.domain.ec2.Ec2Instance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 현재 실행 구간 = 실행 중인 인스턴스의 LaunchTime부터 지금까지.
 *
 * <p>LaunchTime은 stop 후 start하면 바뀌므로 인스턴스 전체 생존 기간이 아니다.
 */
public record CurrentRun(Instant since, BigDecimal hours) {

    public static final String NOTE =
            "현재 실행 구간 기준입니다. 정지 후 다시 시작하면 LaunchTime이 바뀌어 새로 계산됩니다.";

    public static Optional<CurrentRun> of(Ec2Instance instance, Instant now) {
        if (!instance.isRunning() || instance.launchTime() == null || instance.launchTime().isAfter(now)) {
            return Optional.empty();
        }
        long seconds = Duration.between(instance.launchTime(), now).getSeconds();
        BigDecimal hours = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
        return Optional.of(new CurrentRun(instance.launchTime(), hours));
    }
}
