package com.cloudcost.domain.demo;

import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.demo.DemoCatalog.Os;
import com.cloudcost.domain.usage.DateRange;
import java.time.LocalDate;
import java.util.List;

/**
 * 체험 모드에서 사용자가 정한 서버 구성과 조회 기간.
 *
 * @param from 이 날 0시에 서버들을 처음 켰다고 보고, 이 날부터 조회한다
 * @param to   조회 마지막 날 (포함). 오늘보다 뒤일 수 없다
 */
public record DemoSetup(List<Server> servers, LocalDate from, LocalDate to) {

    public static final String INVALID = "DEMO_INVALID_SETUP";
    public static final int MAX_SERVERS = 20;
    public static final int MAX_INSTANCES = 50;
    public static final int MAX_COUNT = 10;
    public static final int MAX_NAME = 64;

    /**
     * 서버 한 줄 = 같은 설정의 인스턴스 count대.
     *
     * @param hoursPerDay DAILY_HOURS일 때 하루 사용 시간 (1~24). 다른 패턴에서는 쓰지 않는다
     */
    public record Server(String name, String instanceType, Os os, int count, DemoSchedule schedule, int hoursPerDay) {

        public Server {
            if (name == null || name.isBlank() || name.strip().length() > MAX_NAME) {
                throw invalid("서버 이름은 1~" + MAX_NAME + "자로 입력해 주세요.");
            }
            name = name.strip();
            if (instanceType == null || DemoCatalog.find(instanceType).isEmpty()) {
                throw invalid("지원하지 않는 인스턴스 타입입니다: " + instanceType);
            }
            if (os == null || schedule == null) {
                throw invalid("OS와 사용 패턴을 골라 주세요.");
            }
            if (count < 1 || count > MAX_COUNT) {
                throw invalid("대수는 1~" + MAX_COUNT + "대로 입력해 주세요.");
            }
            if (schedule == DemoSchedule.DAILY_HOURS && (hoursPerDay < 1 || hoursPerDay > 24)) {
                throw invalid("하루 사용 시간은 1~24시간으로 입력해 주세요.");
            }
        }
    }

    public DemoSetup {
        if (servers == null || servers.isEmpty() || servers.size() > MAX_SERVERS) {
            throw invalid("서버는 1~" + MAX_SERVERS + "개 줄까지 설정할 수 있습니다.");
        }
        if (servers.stream().mapToInt(Server::count).sum() > MAX_INSTANCES) {
            throw invalid("인스턴스는 모두 합쳐 " + MAX_INSTANCES + "대까지 설정할 수 있습니다.");
        }
        if (from == null || to == null) {
            throw invalid("조회 기간을 골라 주세요.");
        }
        servers = List.copyOf(servers);
        new DateRange(from, to);
    }

    /** to가 오늘보다 뒤면 거절한다 (지나간 사용량만 계산한다). */
    public void requireNotAfter(LocalDate today) {
        if (to.isAfter(today)) {
            throw invalid("조회 기간은 오늘까지만 고를 수 있습니다.");
        }
    }

    public DateRange range() {
        return new DateRange(from, to);
    }

    private static DomainValidationException invalid(String message) {
        return new DomainValidationException(INVALID, message);
    }
}
