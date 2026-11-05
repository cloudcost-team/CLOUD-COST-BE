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
 * 팀 벤치마킹 ① 과다 사양 비율의 비교 기준을 Azure Public Dataset V2 원본으로 다시 계산한다 (ADR-013).
 *
 * <p>자료: https://github.com/Azure/AzurePublicDataset/blob/master/AzurePublicDatasetV2.md 의 vmtable.csv.gz
 * (링크: AzurePublicDatasetLinksV2.txt). 마이크로소프트 내부(first-party) 서비스 VM의 30일 기록이다.
 *
 * <p>계산
 * <ul>
 *   <li>구독(= 한 팀으로 본다)을 VM 대수 구간으로 나눈다. 기본 구간은 1-2, 3-20 (--bands 1-2,3-5,6-20처럼 바꿀 수 있다).
 *   <li>과다 사양 VM = p95maxcpu(5분 최대 CPU의 P95) &lt; 10% (--threshold).
 *   <li>구간마다 구독별 과다 사양 VM 비율을 구해 평균·중앙값·백분위 표(5% 단위, nearest-rank)를 낸다.
 * </ul>
 * 출력은 src/main/resources/benchmark/reference.json의 "oversized"를 통째로 바꾸는 JSON이다.
 *
 * <p>실행 (JDK 21, 빌드 없이 단일 파일로 실행. 파일은 한 줄씩 읽는다)
 * <pre>java scripts/benchmark/AzureOversized.java vmtable.csv.gz &gt; oversized.json</pre>
 */
public class AzureOversized {

    // vmtable.csv에는 헤더가 없다. 열: vmid, subscriptionid, deploymentid, vmcreated, vmdeleted,
    // maxcpu, avgcpu, p95maxcpu, vmcategory, vmcorecount, vmmemory
    private static final int SUBSCRIPTION = 1;
    private static final int P95_MAX_CPU = 7;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("사용법: java AzureOversized.java vmtable.csv.gz [--bands 1-2,3-20] [--threshold 10]");
            System.exit(1);
        }
        Path file = Path.of(args[0]);
        String bandsOption = option(args, "--bands");
        List<int[]> bands = parseBands(bandsOption == null ? "1-2,3-20" : bandsOption);
        double threshold = doubleOption(args, "--threshold", 10.0);

        Map<String, int[]> bySubscription = new HashMap<>(); // [전체 VM, 과다 사양 VM]
        try (BufferedReader reader = open(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                int[] counts = bySubscription.computeIfAbsent(cols[SUBSCRIPTION], k -> new int[2]);
                counts[0]++;
                if (Double.parseDouble(cols[P95_MAX_CPU]) < threshold) {
                    counts[1]++;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        for (int[] band : bands) {
            List<Double> ratios = new ArrayList<>();
            for (int[] c : bySubscription.values()) {
                if (c[0] >= band[0] && c[0] <= band[1]) {
                    ratios.add(100.0 * c[1] / c[0]);
                }
            }
            ratios.sort(null);
            out.append(out.isEmpty() ? "" : ",\n").append(bandJson(band[0], band[1], ratios));
        }
        String th = threshold == Math.rint(threshold) ? String.valueOf((long) threshold) : String.valueOf(threshold);
        String json = "{\n"
            + "  \"source\": \"Azure Public Dataset V2\",\n"
            + "  \"description\": \"마이크로소프트 내부 서비스 VM 30일 기록 중 같은 VM 대수 구간 구독들의 과다 사양(5분 최대 CPU의 P95 "
            + th + "% 미만) VM 비율. 측정 방식이 달라 참고용\",\n"
            + "  \"bands\": [\n" + out + "\n  ]\n"
            + "}";
        // 터미널 기본 인코딩과 상관없이 UTF-8로 써서 한글 설명이 깨지지 않게 한다
        new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8).println(json);
    }

    /** 구간 하나. 구독이 없으면 숫자 없는 구간(sampleSize 0)으로 낸다 */
    static String bandJson(int min, int max, List<Double> ratios) {
        String head = "    { \"minServers\": " + min + ", \"maxServers\": " + max + ", ";
        int n = ratios.size();
        if (n == 0) {
            return head + "\"sampleSize\": 0, \"averagePercent\": null, \"medianPercent\": null, \"percentiles\": [] }";
        }
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double median = n % 2 == 1 ? ratios.get(n / 2) : (ratios.get(n / 2 - 1) + ratios.get(n / 2)) / 2;
        StringBuilder percentiles = new StringBuilder();
        for (int p = 5; p < 100; p += 5) {
            int rank = Math.max(1, (int) Math.ceil(p * n / 100.0));
            percentiles.append(percentiles.isEmpty() ? "" : ", ")
                .append(String.format(Locale.ROOT, "{ \"percentile\": %d, \"ratioPercent\": %.1f }", p, ratios.get(rank - 1)));
        }
        return head + "\"sampleSize\": " + n + ", \"averagePercent\": " + Math.round(mean)
            + ", \"medianPercent\": " + Math.round(median) + ",\n      \"percentiles\": [" + percentiles + "] }";
    }

    /** "1-2,3-20" → [[1,2],[3,20]] */
    static List<int[]> parseBands(String value) {
        List<int[]> bands = new ArrayList<>();
        for (String part : value.split(",")) {
            String[] mm = part.trim().split("-");
            bands.add(new int[] {Integer.parseInt(mm[0]), Integer.parseInt(mm[1])});
        }
        return bands;
    }

    private static BufferedReader open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (file.toString().endsWith(".gz")) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }

    private static double doubleOption(String[] args, String name, double fallback) {
        String v = option(args, name);
        return v == null ? fallback : Double.parseDouble(v);
    }

    private static String option(String[] args, String name) {
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return null;
    }
}
