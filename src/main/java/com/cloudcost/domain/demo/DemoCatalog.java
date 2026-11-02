package com.cloudcost.domain.demo;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 체험 모드에서 고를 수 있는 인스턴스 타입과 고정 단가 (서울 리전 온디맨드, USD/시간).
 * 실제 연결은 Price List로 단가를 조회하지만, 체험 모드는 AWS를 부르지 않으므로 고정값을 쓴다.
 */
public final class DemoCatalog {

    public enum Os {
        LINUX("Linux/UNIX"),
        WINDOWS("Windows");

        private final String platformDetails;

        Os(String platformDetails) {
            this.platformDetails = platformDetails;
        }

        public String platformDetails() {
            return platformDetails;
        }
    }

    public record TypePrice(String instanceType, BigDecimal linuxHourlyUsd, BigDecimal windowsHourlyUsd) {

        public BigDecimal hourlyUsd(Os os) {
            return os == Os.WINDOWS ? windowsHourlyUsd : linuxHourlyUsd;
        }
    }

    private static final Map<String, TypePrice> TYPES = new LinkedHashMap<>();

    static {
        add("t3.micro", "0.013", "0.0222");
        add("t3.small", "0.026", "0.0444");
        add("t3.medium", "0.052", "0.0704");
        add("t3.large", "0.104", "0.1336");
        add("m5.large", "0.118", "0.21");
        add("m5.xlarge", "0.236", "0.42");
        add("c5.large", "0.096", "0.188");
        add("c5.xlarge", "0.192", "0.376");
        add("r5.large", "0.152", "0.244");
        add("g4dn.xlarge", "0.647", "0.831");
    }

    private DemoCatalog() {
    }

    private static void add(String type, String linux, String windows) {
        TYPES.put(type, new TypePrice(type, new BigDecimal(linux), new BigDecimal(windows)));
    }

    /** 화면에 보여 줄 순서대로 */
    public static List<TypePrice> list() {
        return List.copyOf(TYPES.values());
    }

    public static Optional<TypePrice> find(String instanceType) {
        return Optional.ofNullable(TYPES.get(instanceType));
    }
}
