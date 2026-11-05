package com.cloudcost.domain.ec2;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 인스턴스 타입 사양 (vCPU·메모리) 고정 카탈로그.
 *
 * <p>고객 계정 권한을 늘리지 않으려고 AWS에서 조회하지 않고, 자주 쓰는 범용·컴퓨팅·메모리 최적화 패밀리만 넣었다.
 * 사양은 타입이 정해지면 바뀌지 않는다. 목록에 없는 타입은 낭비 탐지에서 사양 변경을 추천하지 않는다.
 * 버스터블(t3·t3a) 기본 성능도 함께 둔다 (기능명세서 F05 추천 타입 기준).
 */
public final class InstanceSpecs {

    /** 사양 변경 추천 대상 패밀리. 버스터블이라 CPU를 적게 쓰는 서버에 맞다. */
    public static final String TARGET_FAMILY = "t3";

    /**
     * @param gpu GPU 타입은 CPU 사용률만으로 판단하면 안 되므로 사양 변경을 추천하지 않는다
     */
    public record Spec(String instanceType, int vcpu, BigDecimal memoryGib, boolean gpu) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        // 버스터블: nano ~ 2xlarge
        for (String family : new String[] {"t3", "t3a"}) {
            add(family, "nano", 2, "0.5");
            add(family, "micro", 2, "1");
            add(family, "small", 2, "2");
            add(family, "medium", 2, "4");
            add(family, "large", 2, "8");
            add(family, "xlarge", 4, "16");
            add(family, "2xlarge", 8, "32");
        }
        add("t2", "nano", 1, "0.5");
        add("t2", "micro", 1, "1");
        add("t2", "small", 1, "2");
        add("t2", "medium", 2, "4");
        add("t2", "large", 2, "8");
        add("t2", "xlarge", 4, "16");
        add("t2", "2xlarge", 8, "32");
        // 범용 (vCPU 1개당 4GiB), 컴퓨팅 최적화 (2GiB), 메모리 최적화 (8GiB)
        family(new String[] {"m5", "m5a", "m6i", "m6a", "m7i"}, 4);
        family(new String[] {"c5", "c5a", "c6i", "c6a", "c7i"}, 2);
        family(new String[] {"r5", "r5a", "r6i", "r6a", "r7i"}, 8);
        SPECS.put("g4dn.xlarge", new Spec("g4dn.xlarge", 4, new BigDecimal("16"), true));
        SPECS.put("g4dn.2xlarge", new Spec("g4dn.2xlarge", 8, new BigDecimal("32"), true));
    }

    private InstanceSpecs() {
    }

    private static void add(String family, String size, int vcpu, String memory) {
        String type = family + "." + size;
        SPECS.put(type, new Spec(type, vcpu, new BigDecimal(memory), false));
    }

    private static void family(String[] families, int gibPerVcpu) {
        String[] sizes = {"large", "xlarge", "2xlarge", "4xlarge"};
        int[] vcpus = {2, 4, 8, 16};
        for (String family : families) {
            for (int i = 0; i < sizes.length; i++) {
                add(family, sizes[i], vcpus[i], String.valueOf(vcpus[i] * gibPerVcpu));
            }
        }
    }

    public static Optional<Spec> find(String instanceType) {
        return Optional.ofNullable(instanceType).map(SPECS::get);
    }

    /**
     * 버스터블 타입의 vCPU당 기본 성능 (%). 이 사용률 안에서는 CPU 크레딧을 쓰지 않고 계속 돌 수 있다 (AWS 공개 값).
     * 버스터블이 아니면 empty.
     */
    public static Optional<BigDecimal> baselinePercent(String instanceType) {
        return Optional.ofNullable(instanceType).map(BASELINE::get);
    }

    private static final Map<String, BigDecimal> BASELINE = new LinkedHashMap<>();

    static {
        for (String family : new String[] {"t3", "t3a"}) {
            baseline(family, "nano", "5");
            baseline(family, "micro", "10");
            baseline(family, "small", "20");
            baseline(family, "medium", "20");
            baseline(family, "large", "30");
            baseline(family, "xlarge", "40");
            baseline(family, "2xlarge", "40");
        }
    }

    private static void baseline(String family, String size, String percent) {
        BASELINE.put(family + "." + size, new BigDecimal(percent));
    }

    /**
     * 사양 변경 후보: t3 패밀리에서 지금과 다른 타입 중 메모리가 지금의 절반 이상인 것 (메모리를 절반 아래로 줄이지 않는다).
     * 카탈로그에 없거나 GPU 타입이면 비어 있다. 어느 후보가 맞는지(예상 P95 ≤ 기본 성능, 더 싼지)는 낭비 탐지가 정한다.
     */
    public static List<Spec> downsizeCandidates(String instanceType) {
        Optional<Spec> current = find(instanceType).filter(s -> !s.gpu());
        if (current.isEmpty()) {
            return List.of();
        }
        BigDecimal half = current.get().memoryGib().divide(BigDecimal.valueOf(2));
        return SPECS.values().stream()
            .filter(s -> s.instanceType().startsWith(TARGET_FAMILY + "."))
            .filter(s -> !s.instanceType().equals(instanceType))
            .filter(s -> s.memoryGib().compareTo(half) >= 0)
            .sorted(Comparator.comparing(Spec::memoryGib).thenComparing(Spec::vcpu))
            .toList();
    }
}
