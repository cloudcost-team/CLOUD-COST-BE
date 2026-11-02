package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.DemoUsageService.DemoAccount;
import com.cloudcost.domain.demo.DemoCatalog;
import com.cloudcost.domain.demo.DemoCatalog.Os;
import com.cloudcost.domain.demo.DemoSchedule;
import com.cloudcost.domain.demo.DemoSetup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 체험 모드 요청·응답. 비용 조회 응답은 실제 연결과 같은 {@link UsageDtos}를 쓴다.
 */
public final class DemoDtos {

    private DemoDtos() {}

    public record LoginRequest(
            @Schema(example = "cloudcost") @NotNull @Size(max = 64) String username,
            @Schema(example = "1234") @NotNull @Size(max = 64) String password) {}

    public record DemoAccountResponse(
            String dataSource,
            UUID accountId,
            @Schema(example = "체험용 AWS 계정") String name,
            @Schema(example = "123456789012") String awsAccountId,
            @Schema(example = "ap-northeast-2") String region) {

        public static DemoAccountResponse of(DemoAccount a) {
            return new DemoAccountResponse(UsageDtos.DATA_SOURCE_DEMO, a.accountId(), a.name(), a.awsAccountId(), a.region());
        }
    }

    public record ServerRequest(
            @Schema(example = "web") String name,
            @Schema(example = "t3.medium") String instanceType,
            @Schema(example = "LINUX") Os os,
            @Schema(example = "2") int count,
            @Schema(example = "ALWAYS") DemoSchedule schedule,
            @Schema(description = "schedule이 DAILY_HOURS일 때 하루 사용 시간 (1~24)", example = "8") Integer hoursPerDay) {

        DemoSetup.Server toDomain() {
            return new DemoSetup.Server(name, instanceType, os, count, schedule, hoursPerDay == null ? 0 : hoursPerDay);
        }
    }

    @Schema(description = "체험할 서버 구성과 조회 기간. from 0시에 서버들을 처음 켰다고 보고 계산한다.")
    public record SetupRequest(
            @Schema(example = "2026-10-01") @NotNull LocalDate from,
            @Schema(example = "2026-10-31") @NotNull LocalDate to,
            @NotEmpty @Size(max = DemoSetup.MAX_SERVERS) List<@Valid @NotNull ServerRequest> servers) {

        public DemoSetup toDomain() {
            return new DemoSetup(servers.stream().map(ServerRequest::toDomain).toList(), from, to);
        }
    }

    public record InstanceTypeDto(String instanceType, BigDecimal linuxHourlyUsd, BigDecimal windowsHourlyUsd) {}

    public record ScheduleDto(String code, String label) {}

    public record CatalogResponse(
            List<InstanceTypeDto> instanceTypes,
            List<String> os,
            List<ScheduleDto> schedules,
            int maxServers,
            int maxCountPerServer,
            int maxInstances,
            String priceNote) {

        public static CatalogResponse of(String priceNote) {
            return new CatalogResponse(
                    DemoCatalog.list().stream()
                            .map(t -> new InstanceTypeDto(t.instanceType(), t.linuxHourlyUsd(), t.windowsHourlyUsd()))
                            .toList(),
                    Arrays.stream(Os.values()).map(Enum::name).toList(),
                    Arrays.stream(DemoSchedule.values()).map(s -> new ScheduleDto(s.name(), s.label())).toList(),
                    DemoSetup.MAX_SERVERS, DemoSetup.MAX_COUNT, DemoSetup.MAX_INSTANCES, priceNote);
        }
    }
}
