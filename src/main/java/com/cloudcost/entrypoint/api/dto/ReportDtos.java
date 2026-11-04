package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.application.ReportService.Detail;
import com.cloudcost.application.ReportService.ListView;
import com.cloudcost.application.ReportService.MonthRow;
import com.cloudcost.application.WasteService;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.report.MonthlyReport.Share;
import com.cloudcost.domain.report.MonthlyReport.Status;
import com.cloudcost.domain.spike.SpikeDetector.Spike;
import com.cloudcost.entrypoint.api.dto.UsageDtos.ExchangeRateDto;
import com.cloudcost.entrypoint.api.dto.UsageDtos.FreshnessDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 월간 리포트 (S10-2 목록 · S10 상세 · S10-4 상태). 금액은 요청한 통화로 환산한 추정치다. 프론트는 계산하지 않는다.
 */
public final class ReportDtos {

    private ReportDtos() {}

    static String statusLabel(Status s) {
        return switch (s) {
            case FINAL -> "확정";
            case PREVIEW -> "진행 중";
            case FIRST_MONTH -> "첫 달";
            case EMPTY -> "기록 없음";
        };
    }

    public record MonthSummaryDto(
            @Schema(example = "2026-10") String month,
            @Schema(description = "FINAL(지난 달) | PREVIEW(이번 달) | FIRST_MONTH(전월 없음) | EMPTY(기록 없음)") String status,
            @Schema(example = "확정") String statusLabel,
            BigDecimal usageHours,
            @Schema(description = "그 달 추정 비용. 단가를 아는 서버가 없으면 null", example = "312000") BigDecimal estimatedCost,
            @Schema(description = "전월 추정 비용. 전월이 기록 시작 전이면 null") BigDecimal previousMonthCost,
            @Schema(description = "전월 대비 증감 (반올림한 금액끼리의 차). 비교할 수 없으면 null") BigDecimal change,
            @Schema(description = "전월 대비 증감률 (%). 비교할 수 없거나 전월이 0이면 null", example = "-4.0") BigDecimal changeRate) {

        static MonthSummaryDto of(MonthRow r, DisplayCurrency d) {
            BigDecimal cost = d.cost(r.costUsd());
            BigDecimal previous = d.cost(r.previousCostUsd());
            return new MonthSummaryDto(r.month().toString(), r.status().name(), ReportDtos.statusLabel(r.status()),
                    UsageDtos.hours(r.usedMinutes()), cost, previous,
                    cost == null || previous == null ? null : cost.subtract(previous), r.changeRate());
        }
    }

    public record ReportListResponse(
            String dataSource,
            String costBasis,
            String currency,
            ExchangeRateDto exchangeRate,
            UUID accountId,
            @Schema(description = "기록 시작일. 아직 수집 전이면 null (months도 비어 있다)") LocalDate availableFrom,
            @Schema(description = "기록 시작 달 ~ 이번 달, 최신 달 먼저 (최대 14개월)") List<MonthSummaryDto> months,
            FreshnessDto freshness,
            List<String> notes,
            Instant asOf) {

        public static ReportListResponse of(ListView v, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            return new ReportListResponse(dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d),
                    v.accountId(), v.availableFrom(), v.months().stream().map(r -> MonthSummaryDto.of(r, d)).toList(),
                    FreshnessDto.of(v.freshness()), ReportDtos.notes(v.notes(), currency, dataSource), v.asOf());
        }
    }

    public record SpikeDayDto(
            LocalDate date, BigDecimal ratio, String causeInstanceId, String causeName,
            @Schema(example = "10/12 비용 급증 · 평소의 2.4배 · web-server-1 사용 시간이 늘었어요") String message) {}

    public record SpikesDto(@Schema(description = "급증일 수") int count, List<SpikeDayDto> days) {}

    public record SavingItemDto(
            String recommendationId, String instanceId, String name, String kind, String kindLabel, String action,
            BigDecimal monthlySavings) {}

    @Schema(description = "절감 제안: 그 달 마지막 14일에 낭비 탐지 규칙 적용. EMPTY 달이면 null")
    public record SavingsDto(
            @Schema(description = "OK | NONE_FOUND | INSUFFICIENT_HISTORY") String status,
            WasteDtos.WindowDto window,
            int count,
            @Schema(description = "매달 절감 예상 합계 (currency 기준)") BigDecimal monthlySavings,
            List<SavingItemDto> items) {

        static SavingsDto of(WasteService.WasteView w, DisplayCurrency d) {
            if (w == null) {
                return null;
            }
            List<SavingItemDto> items = w.result().findings().stream()
                    .map(f -> new SavingItemDto(f.recommendationId(), f.instance().instanceId(), f.displayName(),
                            f.kind().name(), f.kind().label(), f.action(), d.cost(f.monthlySavingsUsd())))
                    .toList();
            return new SavingsDto(w.status().name(), new WasteDtos.WindowDto(w.windowStart(), w.windowEnd()),
                    items.size(), d.cost(w.result().totalMonthlySavingsUsd()), items);
        }
    }

    public record ServerShareDto(
            @Schema(description = "기타면 null") String instanceId,
            @Schema(description = "기타면 \"기타\"") String name,
            String instanceType,
            @Schema(description = "기타 줄이면 true") boolean others,
            BigDecimal usageHours,
            BigDecimal estimatedCost,
            @Schema(description = "그 달 추정 비용 중 비중 (%)", example = "42.5") BigDecimal share) {

        static ServerShareDto of(Share s, Detail detail, DisplayCurrency d) {
            TrackedInstance t = s.others() ? null : detail.names().get(s.instanceId());
            String name = s.others() ? "기타" : t == null ? s.instanceId() : t.info().name();
            return new ServerShareDto(s.instanceId(), name, t == null ? null : t.info().instanceType(), s.others(),
                    UsageDtos.hours(s.usedMinutes()), d.cost(s.costUsd()), s.sharePercent());
        }
    }

    public record ReportDetailResponse(
            String dataSource,
            String costBasis,
            String currency,
            ExchangeRateDto exchangeRate,
            UUID accountId,
            @Schema(example = "2026-10") String month,
            String status,
            String statusLabel,
            @Schema(description = "리포트 첫날 (첫 달이면 기록 시작일)") LocalDate from,
            @Schema(description = "리포트 마지막 날 (이번 달이면 오늘)") LocalDate to,
            MonthSummaryDto summary,
            SpikesDto spikes,
            SavingsDto savings,
            @Schema(description = "비용 상위 3대 + 기타") List<ServerShareDto> topServers,
            FreshnessDto freshness,
            List<String> notes,
            Instant asOf) {

        public static ReportDetailResponse of(Detail v, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            List<SpikeDayDto> spikes = new ArrayList<>();
            for (Spike s : v.spikes()) {
                String cause = s.causeInstanceId() == null ? null : v.spikeCauseNames().get(s.causeInstanceId());
                spikes.add(new SpikeDayDto(s.date(), s.ratio(), s.causeInstanceId(), cause, s.message(cause)));
            }
            Status status = v.row().status();
            return new ReportDetailResponse(dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d),
                    v.accountId(), v.row().month().toString(), status.name(), ReportDtos.statusLabel(status), v.from(), v.to(),
                    MonthSummaryDto.of(v.row(), d), new SpikesDto(spikes.size(), spikes), SavingsDto.of(v.savings(), d),
                    v.topServers().stream().map(s -> ServerShareDto.of(s, v, d)).toList(),
                    FreshnessDto.of(v.freshness()), ReportDtos.notes(v.notes(), currency, dataSource), v.asOf());
        }
    }

    private static List<String> notes(List<String> base, Resolved currency, String dataSource) {
        List<String> notes = new ArrayList<>();
        if (UsageDtos.DATA_SOURCE_DEMO.equals(dataSource)) {
            notes.add(DemoFleet.NOTE);
            notes.add("체험 모드는 설정한 기간의 마지막 날을 오늘로 보고 계산합니다.");
        }
        notes.addAll(base);
        if (currency.display().conversionNote() != null) {
            notes.add(currency.display().conversionNote());
        }
        notes.addAll(currency.warnings());
        return List.copyOf(notes);
    }
}
