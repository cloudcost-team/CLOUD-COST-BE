package com.cloudcost.application;

import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceSpecs;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.usage.CpuSeries;
import com.cloudcost.domain.usage.HourlySample;
import com.cloudcost.domain.usage.UsageCalculator;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.domain.waste.WasteDetector;
import com.cloudcost.domain.waste.WasteDetector.Candidate;
import com.cloudcost.domain.waste.WasteDetector.Result;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 낭비 리소스 탐지 (이슈 #10). 최근 14일 사용률(#24)과 온디맨드 단가로 {@link WasteDetector}를 돌린다.
 * 실제 연결과 체험 모드가 같은 {@link #analyze} 계산을 쓴다.
 */
public class WasteService {

    /** 지금 권한으로 찾을 수 없는 낭비 (S07 하단 안내) */
    public static final String NOT_COVERED =
        "미연결 볼륨 · 미사용 고정 IP · 스냅샷 · 로드밸런서는 지금 연결 권한(EC2·CloudWatch 읽기)으로는 찾을 수 없어요.";
    public static final String BASIS =
        "최근 14일 CloudWatch 기본 지표(CPU·네트워크)로 판단했어요. CPU P95는 1시간 평균값 기준이에요.";
    public static final String MONTHLY_BASIS =
        "월 추정 비용은 최근 14일 사용 시간을 한 달(730시간)로 환산해 온디맨드 단가를 곱한 값이에요.";
    public static final String ADVICE_ONLY = "추천은 조언이에요. 실제 변경은 AWS 콘솔에서 직접 해 주세요.";

    public enum Status {
        /** 추천이 있음 */
        OK,
        /** 판단했지만 줄일 만한 서버가 없음 */
        NONE_FOUND,
        /** 기록 시작일부터 14일이 안 됨. availableAt부터 판단한다 */
        INSUFFICIENT_HISTORY
    }

    /**
     * @param availableAt  INSUFFICIENT_HISTORY일 때 판단을 시작하는 날 (그 밖은 null)
     * @param windowStart  판단 구간 시작 (포함)
     * @param windowEnd    판단 구간 끝 (제외)
     * @param instances    판단에 넣은 인스턴스 (남아 있는 것)
     * @param stats        instanceId → 최근 14일 사용률 (벤치마킹도 같은 값을 쓴다). INSUFFICIENT_HISTORY면 비어 있다
     * @param cpuSeries    instanceId → 시간별 CPU (S07-2 그래프). 추천이 있는 서버만 들어 있다
     */
    public record WasteView(
        UUID accountId,
        Status status,
        LocalDate availableAt,
        Instant windowStart,
        Instant windowEnd,
        Result result,
        List<Ec2Instance> instances,
        Map<String, UtilizationStats> stats,
        Map<String, CpuSeries> cpuSeries,
        Freshness freshness,
        List<String> notes,
        Instant asOf) {
    }

    private final AwsAccountService accountService;
    private final Ec2DataRefresher refresher;
    private final Ec2InstanceRepository instances;
    private final Ec2UsageRepository usage;
    private final PriceService prices;
    private final Clock clock;
    private final UsageSettings settings;

    public WasteService(
        AwsAccountService accountService,
        Ec2DataRefresher refresher,
        Ec2InstanceRepository instances,
        Ec2UsageRepository usage,
        PriceService prices,
        Clock clock,
        UsageSettings settings) {
        this.accountService = accountService;
        this.refresher = refresher;
        this.instances = instances;
        this.usage = usage;
        this.prices = prices;
        this.clock = clock;
        this.settings = settings;
    }

    public WasteView waste(String ownerId, UUID accountId) {
        return wasteUntil(ownerId, accountId, clock.instant().truncatedTo(ChronoUnit.HOURS));
    }

    /**
     * end 직전 14일로 판단한다 (월간 리포트의 "그 달 마지막 14일"). 대상 서버는 지금 남아 있는 서버다.
     *
     * @param end 판단 구간 끝 (제외). 지금 정시보다 늦으면 지금 정시로 줄인다
     */
    public WasteView wasteUntil(String ownerId, UUID accountId, Instant end) {
        accountService.getActive(ownerId, accountId);
        Freshness freshness = refresher.refreshIfStale(ownerId, accountId);
        Instant now = clock.instant();
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        if (end.isAfter(currentHour)) {
            end = currentHour;
        }
        Instant start = end.minus(UtilizationStats.WINDOW);

        List<TrackedInstance> tracked = instances.findAllByOwnerIdAndAccountId(ownerId, accountId).stream()
            .filter(TrackedInstance::present)
            .toList();
        List<Ec2Instance> infos = tracked.stream().map(TrackedInstance::info).toList();

        // 지금 타입과 사양 변경 후보 타입의 단가를 한 번에 조회한다 (후보는 같은 OS·테넌시의 가상 인스턴스로 묻는다).
        List<Ec2Instance> toPrice = new ArrayList<>(infos);
        infos.forEach(i -> InstanceSpecs.downsizeCandidates(i.instanceType())
            .forEach(t -> toPrice.add(asType(i, t.instanceType()))));
        PriceService.PriceResult priced = prices.pricesFor(toPrice);
        Map<String, InstancePrice> byId = priced.byInstanceId();

        LocalDate availableFrom = Ec2UsageQueryService.availableFrom(tracked, settings);
        List<HourlySample> samples = usage.findHourly(ownerId, accountId, start, end);
        return analyze(accountId, infos, samples, start, end, availableFrom,
            i -> hourly(byId.get(i.instanceId())),
            i -> candidatePrices(i, byId),
            freshness.withWarnings(Freshness.WarningCode.PRICE_UNAVAILABLE, priced.warnings()), now, settings);
    }

    /**
     * 실제 연결·체험 모드 공통 계산.
     *
     * @param availableFrom 기록 시작일. null이면 아직 본 인스턴스가 없다
     * @param hourly        인스턴스 → 지금 타입 단가 (모르면 null)
     * @param candidateHourly 인스턴스 → 사양 변경 후보 타입별 단가 (모르는 타입은 빠진다)
     */
    static WasteView analyze(
        UUID accountId, List<Ec2Instance> infos, List<HourlySample> windowSamples, Instant start, Instant end,
        LocalDate availableFrom, Function<Ec2Instance, BigDecimal> hourly,
        Function<Ec2Instance, Map<String, BigDecimal>> candidateHourly, Freshness freshness, Instant asOf,
        UsageSettings settings) {
        // 판단 구간 끝 날짜 기준: 실제 연결은 오늘, 체험 모드는 설정한 기간의 다음 날 0시
        LocalDate today = LocalDate.ofInstant(end, settings.zone());
        List<String> notes = List.of(BASIS, MONTHLY_BASIS, ADVICE_ONLY, NOT_COVERED);
        LocalDate availableAt = availableFrom == null ? null : availableFrom.plusDays(UtilizationStats.WINDOW.toDays());
        if (availableAt != null && availableAt.isAfter(today)) {
            return new WasteView(accountId, Status.INSUFFICIENT_HISTORY, availableAt, start, end,
                new Result(List.of(), List.of(), java.util.Set.of()), List.copyOf(infos), Map.of(), Map.of(), freshness, notes,
                asOf);
        }

        Map<String, UtilizationStats> stats = UtilizationStats.byInstance(windowSamples);
        Map<String, Long> minutes = new HashMap<>();
        Map<String, Instant> lastUsed = new HashMap<>();
        for (HourlySample s : windowSamples) {
            long used = UsageCalculator.usedMinutes(s.sampleCount());
            minutes.merge(s.instanceId(), used, Long::sum);
            if (used > 0) {
                lastUsed.merge(s.instanceId(), s.hourStart(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        List<Candidate> candidates = infos.stream()
            .map(i -> new Candidate(i, stats.get(i.instanceId()), minutes.getOrDefault(i.instanceId(), 0L),
                hourly.apply(i), candidateHourly.apply(i), lastUsed.get(i.instanceId())))
            .toList();
        Result result = WasteDetector.detect(candidates, end);
        Status status = result.findings().isEmpty() ? Status.NONE_FOUND : Status.OK;
        Map<String, CpuSeries> series = new HashMap<>();
        result.findings().forEach(f -> series.put(f.instance().instanceId(), CpuSeries.of(windowSamples.stream()
            .filter(s -> s.instanceId().equals(f.instance().instanceId())).toList())));
        return new WasteView(accountId, status, null, start, end, result, List.copyOf(infos), stats, Map.copyOf(series),
            freshness, notes, asOf);
    }

    private static BigDecimal hourly(InstancePrice price) {
        return price == null ? null : price.hourlyUsd();
    }

    private static String targetId(String instanceId, String instanceType) {
        return instanceId + "#" + instanceType;
    }

    private static Map<String, BigDecimal> candidatePrices(Ec2Instance i, Map<String, InstancePrice> byId) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (InstanceSpecs.Spec t : InstanceSpecs.downsizeCandidates(i.instanceType())) {
            BigDecimal price = hourly(byId.get(targetId(i.instanceId(), t.instanceType())));
            if (price != null) {
                result.put(t.instanceType(), price);
            }
        }
        return result;
    }

    /** 단가 조회용: 같은 OS·테넌시·수명주기로 타입만 바꾼 가상 인스턴스 */
    private static Ec2Instance asType(Ec2Instance i, String instanceType) {
        return new Ec2Instance(targetId(i.instanceId(), instanceType), i.name(), instanceType, i.region(), i.availabilityZone(),
            i.state(), i.launchTime(), i.platformDetails(), i.tenancy(), i.spot(), i.detailedMonitoring());
    }
}
