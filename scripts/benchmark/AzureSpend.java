import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 팀 벤치마킹 ③ 규모 대비 월 지출의 비교 기준을 Azure Public Dataset V2 원본으로 계산한다 (ADR-013).
 *
 * <p>자료: AzureOversized.java와 같은 vmtable.csv.gz. 마이크로소프트 내부(first-party) 서비스 VM의 30일 기록이다.
 *
 * <p>금액이 아니라 <b>한 달 사용량(vCPU-시간 = vCPU × 켜진 시간)</b>으로 분포를 낸다. 원본에는 가격이 없고 코어 수도 구간뿐이라
 * AWS 금액으로 바꾸면 단가 가정에 따라 크게 달라지기 때문이다. 우리 팀도 같은 단위·같은 코어 구간으로 재서 순위를 매기면
 * 단가 가정이 순위에 영향을 주지 않는다 (금액 범위는 앱이 우리 팀의 실제 vCPU-시간당 비용으로 환산해 보여 준다).
 *
 * <p>계산
 * <ul>
 *   <li>구독(= 한 팀으로 본다)마다 30일 동안 1시간 이상 켜진 VM만 센다 (우리 쪽도 1시간 단위 기록이 있는 서버만 센다).
 *   <li>VM의 vCPU = 코어 구간 대표값 (2·4·8·24, "&gt;24"는 32). 앱의 {@code SpendBenchmark.vcpuBucket}과 같은 규칙이다.
 *   <li>구독의 한 달 사용량 = Σ vCPU × 켜진 시간(vmdeleted − vmcreated, 30일 창 안). 서버 대수 = 센 VM 수.
 *   <li>서버 대수 구간마다 사용량의 중앙값과 백분위 표(5% 단위, nearest-rank)를 낸다.
 * </ul>
 * 출력은 src/main/resources/benchmark/reference.json의 "spend"를 통째로 바꾸는 JSON이다.
 *
 * <p>실행 (JDK 21, 빌드 없이 단일 파일로 실행)
 * <pre>java scripts/benchmark/AzureSpend.java vmtable.csv.gz &gt; spend.json</pre>
 */
public class AzureSpend {

    // vmtable.csv에는 헤더가 없다. 열: vmid, subscriptionid, deploymentid, vmcreated, vmdeleted,
    // maxcpu, avgcpu, p95maxcpu, vmcategory, vmcorecount, vmmemory (시각은 기록 시작부터의 초, 0~2,592,000)
    private static final int SUBSCRIPTION = 1;
    private static final int CREATED = 3;
    private static final int DELETED = 4;
    private static final int CORES = 9;
    private static final long WINDOW_SECONDS = 30L * 24 * 3600;
    private static final long MIN_SECONDS = 3600;
    /** 기록 간격 2칸(10분). 마지막 기록 시각이 이 안이면 끝까지 켜진 VM이다 */
    private static final long LAST_SLOT_SECONDS = 600;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("사용법: java AzureSpend.java vmtable.csv.gz [--bands 1-2,3-20]");
            System.exit(1);
        }
        String bandsOption = option(args, "--bands");
        List<int[]> bands = parseBands(bandsOption == null ? "1-2,3-20" : bandsOption);

        Map<String, double[]> bySubscription = new HashMap<>(); // [VM 수, vCPU-시간]
        long rows = 0;
        long skippedShort = 0;
        try (BufferedReader reader = open(Path.of(args[0]))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rows++;
                String[] cols = line.split(",", -1);
                long created = Math.max(0, (long) Double.parseDouble(cols[CREATED]));
                long deleted = (long) Double.parseDouble(cols[DELETED]);
                // 기록은 5분 간격이라 끝까지 켜진 VM의 마지막 시각은 2,591,700초 근처다. 끝 칸이면 30일 끝까지 켜진 것으로 본다
                // (그러지 않으면 한 달 내내 켜진 2vCPU 1대가 1,439.7이 되어, 같은 조건의 우리 팀 1,440보다 "적게 쓴 팀"으로 잡힌다)
                deleted = deleted >= WINDOW_SECONDS - LAST_SLOT_SECONDS ? WINDOW_SECONDS : deleted;
                long seconds = deleted - created;
                if (seconds < MIN_SECONDS) {
                    skippedShort++;
                    continue;
                }
                double[] acc = bySubscription.computeIfAbsent(cols[SUBSCRIPTION], k -> new double[2]);
                acc[0]++;
                acc[1] += vcpuOfBucket(cols[CORES]) * (seconds / 3600.0);
            }
        }
        System.err.printf(Locale.ROOT, "행 %d개, 1시간 미만 VM %d개 제외, 구독 %d곳%n", rows, skippedShort,
            bySubscription.size());

        StringBuilder out = new StringBuilder();
        for (int[] band : bands) {
            List<Double> usage = new ArrayList<>();
            for (double[] acc : bySubscription.values()) {
                if (acc[0] >= band[0] && acc[0] <= band[1]) {
                    usage.add(acc[1]);
                }
            }
            usage.sort(null);
            out.append(out.isEmpty() ? "" : ",\n").append(bandJson(band[0], band[1], usage));
        }
        String json = "{\n"
            + "  \"source\": \"Azure Public Dataset V2\",\n"
            + "  \"description\": \"마이크로소프트 내부 서비스 VM 30일 기록 중 같은 서버 대수 구간 구독들의 한 달 사용량"
            + "(vCPU × 켜진 시간). 금액은 우리 팀의 vCPU-시간당 비용으로 환산한다. 대상이 달라 참고용\",\n"
            + "  \"bands\": [\n" + out + "\n  ]\n}\n";
        System.out.print(json);
    }

    /** 코어 구간 대표값. 앱의 SpendBenchmark.vcpuBucket과 같은 규칙이어야 순위가 단가 가정과 무관해진다 */
    static int vcpuOfBucket(String bucket) {
        return switch (bucket.trim()) {
            case "2" -> 2;
            case "4" -> 4;
            case "8" -> 8;
            case "24" -> 24;
            case ">24" -> 32;
            default -> throw new IllegalArgumentException("모르는 코어 구간: " + bucket);
        };
    }

    private static String bandJson(int min, int max, List<Double> sorted) {
        StringBuilder b = new StringBuilder();
        b.append("    {\n");
        b.append("      \"minServers\": ").append(min).append(",\n");
        b.append("      \"maxServers\": ").append(max).append(",\n");
        b.append("      \"sampleSize\": ").append(sorted.size()).append(",\n");
        if (sorted.isEmpty()) {
            b.append("      \"medianVcpuHours\": null,\n      \"percentiles\": []\n    }");
            return b.toString();
        }
        b.append("      \"medianVcpuHours\": ").append(fmt(nearestRank(sorted, 50))).append(",\n");
        b.append("      \"percentiles\": [\n");
        for (int p = 5; p <= 95; p += 5) {
            b.append("        { \"percentile\": ").append(p).append(", \"vcpuHours\": ")
                .append(fmt(nearestRank(sorted, p))).append(" }").append(p < 95 ? ",\n" : "\n");
        }
        b.append("      ]\n    }");
        return b.toString();
    }

    private static double nearestRank(List<Double> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static List<int[]> parseBands(String spec) {
        List<int[]> bands = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] mm = part.trim().split("-");
            bands.add(new int[] {Integer.parseInt(mm[0]), Integer.parseInt(mm[1])});
        }
        return bands;
    }

    private static String option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static BufferedReader open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (file.toString().endsWith(".gz")) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }
}
