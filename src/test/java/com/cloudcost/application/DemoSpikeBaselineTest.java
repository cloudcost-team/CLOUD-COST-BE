package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.domain.demo.DemoCatalog.Os;
import com.cloudcost.domain.demo.DemoSchedule;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.demo.DemoSetup.Server;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 체험 모드도 실제 연결처럼 처음 사용한 날부터 급증의 평소 비용을 잰다 (#40 리뷰) */
class DemoSpikeBaselineTest extends IntegrationTestBase {

    @Autowired DemoUsageService demo;

    @Test
    void 토요일에_시작한_평일_일정은_월요일이_처음_사용한_날이다() {
        // 2026-08-01은 토요일. 평일 09~19시 서버는 8/3(월)에 처음 켜진다
        DemoSetup setup = new DemoSetup(
            List.of(new Server("web", "t3.medium", Os.LINUX, 1, DemoSchedule.WEEKDAY_OFFICE, 0)),
            LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 7));

        assertThat(DemoUsageService.firstUsedDay(demo.daily(setup))).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(demo.dailyWithSpikes(setup).spikes()).as("평소대로 쓰면 급증 없음").isEmpty();
    }
}
