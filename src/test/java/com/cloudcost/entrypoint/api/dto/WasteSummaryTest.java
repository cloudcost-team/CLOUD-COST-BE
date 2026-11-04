package com.cloudcost.entrypoint.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceSpecs;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteFinding.DownsizeBasis;
import com.cloudcost.domain.waste.WasteKind;
import com.cloudcost.entrypoint.api.dto.WasteDtos.ItemDto;
import com.cloudcost.entrypoint.api.dto.WasteDtos.SummaryLineDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S07-2 10초 요약: 제목은 사용량 구간에 따라, 설명은 서버마다 다른 숫자로 */
class WasteSummaryTest {

    private static final DisplayCurrency USD = DisplayCurrency.usd();
    private static final Ec2Instance API = new Ec2Instance("i-api", "api", "m5.large", "ap-northeast-2", "ap-northeast-2a",
            "running", Instant.parse("2026-09-01T00:00:00Z"), "Linux/UNIX", "default", false, false);

    private static WasteFinding oversized(String avg, String max, String projected, String nextType) {
        UtilizationStats stats = new UtilizationStats("i-api", 336, new BigDecimal(avg), new BigDecimal(projected),
                new BigDecimal(max), 336, 10_000_000_000L);
        var nextSpec = nextType == null ? null : InstanceSpecs.find(nextType).orElseThrow();
        return new WasteFinding(API, WasteKind.OVERSIZED, stats, BigDecimal.valueOf(730),
                InstanceSpecs.find("m5.large").orElseThrow(), new BigDecimal("0.118"), new BigDecimal("86.14"),
                InstanceSpecs.find("t3.medium").orElseThrow(), new BigDecimal("0.052"), new BigDecimal("37.96"),
                new BigDecimal("48.18"), "r",
                new DownsizeBasis(new BigDecimal(projected), BigDecimal.valueOf(20), nextSpec,
                        nextType == null ? null : new BigDecimal("0.104")));
    }

    private static List<String> titles(WasteFinding f) {
        return ItemDto.summary(f, USD).stream().map(SummaryLineDto::title).toList();
    }

    @Test
    void 와이어프레임_경우는_거의_안_쓰고_넉넉하고_가장_싸다() {
        WasteFinding f = oversized("6", "23", "9.0", "t3.large");

        assertThat(titles(f)).containsExactly("거의 안 쓰고 있어요", "줄여도 넉넉해요", "조건을 만족하는 것 중 가장 싸요");
        assertThat(ItemDto.summary(f, USD).get(0).detail()).isEqualTo("14일 평균 CPU 6%, 가장 높을 때도 23%예요");
    }

    @Test
    void 사용량과_여유와_후보에_따라_제목이_바뀐다() {
        assertThat(titles(oversized("0.5", "3", "1.0", null)))
                .containsExactly("사실상 쉬고 있어요", "줄여도 넉넉해요", "조건을 만족하는 타입은 이것뿐이에요");
        assertThat(titles(oversized("9", "35", "15.0", "t3.large")))
                .containsExactly("적게 쓰고 있어요", "줄여도 기본 성능 안이에요", "조건을 만족하는 것 중 가장 싸요");
        assertThat(ItemDto.summary(oversized("9", "80", "15.0", null), USD).get(0).detail())
                .as("최대가 높으면 '가장 높을 때도'라고 하지 않는다").isEqualTo("14일 평균 CPU 9%, 가장 높을 땐 80%까지 올라갔어요");
        assertThat(ItemDto.summary(oversized("6", "23", "9.0", null), USD).get(2).detail()).isEqualTo("시간당 $0.052");
    }

    @Test
    void 유휴는_최대_CPU와_하루_네트워크로_제목과_설명이_바뀐다() {
        UtilizationStats quiet = new UtilizationStats("i-api", 336, new BigDecimal("0.4"), new BigDecimal("0.5"),
                new BigDecimal("0.8"), 336, 14L * 3 * 1024 * 1024);
        WasteFinding idle = new WasteFinding(API, WasteKind.IDLE, quiet, BigDecimal.valueOf(730),
                InstanceSpecs.find("m5.large").orElseThrow(), new BigDecimal("0.118"), new BigDecimal("86.14"),
                null, null, null, new BigDecimal("86.14"), "r");

        List<SummaryLineDto> lines = ItemDto.summary(idle, USD);
        assertThat(lines).extracting(SummaryLineDto::title)
                .containsExactly("사실상 쉬고 있어요", "네트워크도 조용해요", "중지하면 그만큼 줄어요");
        assertThat(lines.get(0).detail()).isEqualTo("14일 동안 CPU가 가장 높을 때도 1% 미만이에요");
        assertThat(lines.get(1).detail()).isEqualTo("하루 평균 입출력이 3MB예요");
        assertThat(lines.get(2).detail()).isEqualTo("매달 $86.14 절감 예상 (추정)");
    }
}
