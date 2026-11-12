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
 * 팀 벤치마킹 ③ 규모 대비 월 지출의 비교 모델을 Azure Public Dataset V2 원본으로 학습한다 (ADR-013).
 *
 * <p>자료: AzureOversized.java와 같은 vmtable.csv.gz. 마이크로소프트 내부(first-party) 서비스 VM의 30일 기록이다.
 *
 * <p>무엇을 예측하나: 서버 대수 s를 넣으면 "그 규모 팀의 한 달 사용량(vCPU-시간 = vCPU × 켜진 시간)의 p번째 백분위"를 낸다.
 * 금액이 아니라 사용량인 이유는 원본에 가격이 없고 코어 수도 구간뿐이라서다. 우리 팀도 같은 단위·같은 코어 구간으로 재서
 * 순위를 매기면 단가 가정이 순위에 영향을 주지 않는다.
 *
 * <p>모델: 백분위마다 따로 학습한 <b>선형 분위 회귀</b> (quantile regression).
 * <pre>ln(사용량)의 p번째 백분위 = a + b·ln(s) + c·ln(s)²</pre>
 * 손실은 pinball loss이고, MM 알고리즘(반복 가중 최소제곱, Hunter &amp; Lange 2000)으로 푼다. 외부 라이브러리 없이 JDK만 쓴다.
 * 예측할 때 백분위끼리 순서가 뒤집히면 정렬해 바로잡는다 (rearrangement).
 *
 * <p>검증: 구독을 id 해시로 8:2로 나눠 학습용으로만 학습하고 시험용으로 잰다.
 * <ul>
 *   <li>pinball loss (19개 백분위 평균, ln 공간) — 기존 방식(서버 대수 구간별 백분위 표)과 비교
 *   <li>25~75번째 백분위 범위에 실제 값이 들어온 비율 (잘 맞으면 50% 근처)
 * </ul>
 * 검증 뒤 전체 자료로 다시 학습한 계수를 출력한다.
 *
 * <p>사용량 계산
 * <ul>
 *   <li>구독(= 한 팀으로 본다)마다 30일 동안 1시간 이상 켜진 VM만 센다 (우리 쪽도 1시간 단위 기록이 있는 서버만 센다).
 *   <li>VM의 vCPU = 코어 구간 대표값 (2·4·8·24, "&gt;24"는 32). 앱의 {@code SpendBenchmark.vcpuBucket}과 같은 규칙이다.
 *   <li>기록 끝 칸까지 켜진 VM은 30일 내내 켜진 것으로 본다.
 * </ul>
 * 출력은 src/main/resources/benchmark/reference.json의 "spend"를 통째로 바꾸는 JSON이다. 검증 결과는 표준 오류로도 찍는다.
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

    /** 학습하는 서버 대수 범위. 이보다 큰 팀은 자료가 드물어 비교하지 않는다 */
    static final int MIN_SERVERS = 1;
    static final int MAX_SERVERS = 50;
    static final int[] PERCENTILES = {5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95};
    /** 기존 방식(구간별 백분위 표)과 견줄 때 쓰는 구간 */
    private static final int[][] BASELINE_BANDS = {{1, 2}, {3, 20}, {21, 50}};

    record Team(String id, int servers, double vcpuHours) {
        double x() {
            return Math.log(servers);
        }

        double y() {
            return Math.log(vcpuHours);
        }

        boolean test() {
            return Math.floorMod(id.hashCode(), 5) == 0;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("사용법: java AzureSpend.java vmtable.csv.gz");
            System.exit(1);
        }
        List<Team> teams = teams(Path.of(args[0]));
        List<Team> train = teams.stream().filter(t -> !t.test()).toList();
        List<Team> test = teams.stream().filter(Team::test).toList();

        // 1) 학습용으로 학습 → 시험용으로 검증
        double[][] trainCoef = fitAll(train);
        double modelLoss = 0;
        double baselineLoss = 0;
        int covered = 0;
        Map<String, double[]> baselineTables = baselineTables(train);
        for (Team t : test) {
            double[] predicted = predict(trainCoef, t.servers());
            double[] baseline = baselineTables.get(bandKey(t.servers()));
            for (int i = 0; i < PERCENTILES.length; i++) {
                double tau = PERCENTILES[i] / 100.0;
                modelLoss += pinball(t.y() - predicted[i], tau);
                baselineLoss += pinball(t.y() - baseline[i], tau);
            }
            if (t.y() >= predicted[index(25)] && t.y() <= predicted[index(75)]) {
                covered++;
            }
        }
        int n = test.size() * PERCENTILES.length;
        modelLoss /= n;
        baselineLoss /= n;
        double coverage = 100.0 * covered / test.size();
        System.err.printf(Locale.ROOT,
            "팀 %d곳 (학습 %d · 시험 %d). 시험용 pinball loss: 분위 회귀 %.4f, 구간별 백분위 표 %.4f (%.1f%% 개선). "
                + "25~75번째 범위 적중 %.1f%% (목표 50%%)%n",
            teams.size(), train.size(), test.size(), modelLoss, baselineLoss,
            100 * (baselineLoss - modelLoss) / baselineLoss, coverage);

        // 2) 전체 자료로 다시 학습해 앱에 넣는다
        double[][] coef = fitAll(teams);
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < PERCENTILES.length; i++) {
            q.append(i == 0 ? "" : ",\n").append("        { \"percentile\": ").append(PERCENTILES[i])
                .append(", \"coefficients\": [").append(num(coef[i][0])).append(", ").append(num(coef[i][1]))
                .append(", ").append(num(coef[i][2])).append("] }");
        }
        StringBuilder preview = new StringBuilder();
        for (int s : new int[] {1, 2, 3, 5, 10, 20, 50}) {
            double[] p = predict(coef, s);
            preview.append(String.format(Locale.ROOT, "서버 %d대 → 25%% %.0f · 50%% %.0f · 75%% %.0f%n", s,
                Math.exp(p[index(25)]), Math.exp(p[index(50)]), Math.exp(p[index(75)])));
        }
        System.err.print(preview);

        String json = "{\n"
            + "  \"source\": \"Azure Public Dataset V2\",\n"
            + "  \"description\": \"마이크로소프트 내부 서비스 VM 30일 기록(구독 " + teams.size()
            + "곳)으로 학습한 분위 회귀 모델. 서버 대수로 같은 규모 팀의 한 달 사용량(vCPU × 켜진 시간) 분포를 예측한다. "
            + "금액은 우리 팀의 vCPU-시간당 비용으로 환산한다. 대상이 달라 참고용\",\n"
            + "  \"model\": {\n"
            + "    \"method\": \"QUANTILE_REGRESSION\",\n"
            + "    \"formula\": \"ln(vcpuHours)의 p번째 백분위 = c0 + c1·ln(servers) + c2·ln(servers)²\",\n"
            + "    \"minServers\": " + MIN_SERVERS + ",\n"
            + "    \"maxServers\": " + MAX_SERVERS + ",\n"
            + "    \"sampleSize\": " + teams.size() + ",\n"
            + "    \"validation\": {\n"
            + "      \"trainSize\": " + train.size() + ",\n"
            + "      \"testSize\": " + test.size() + ",\n"
            + "      \"pinballLoss\": " + num(modelLoss) + ",\n"
            + "      \"baselinePinballLoss\": " + num(baselineLoss) + ",\n"
            + "      \"baseline\": \"서버 대수 구간(1-2, 3-20, 21-50)별 백분위 표\",\n"
            + "      \"middleHalfCoveragePercent\": " + String.format(Locale.ROOT, "%.1f", coverage) + "\n"
            + "    },\n"
            + "    \"quantiles\": [\n" + q + "\n    ]\n"
            + "  }\n"
            + "}\n";
        System.out.print(json);
    }

    // ---------- 자료 ----------

    static List<Team> teams(Path file) throws IOException {
        Map<String, double[]> bySubscription = new HashMap<>(); // [VM 수, vCPU-시간]
        try (BufferedReader reader = open(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                long created = Math.max(0, (long) Double.parseDouble(cols[CREATED]));
                long deleted = (long) Double.parseDouble(cols[DELETED]);
                // 기록은 5분 간격이라 끝까지 켜진 VM의 마지막 시각은 2,591,400초 근처다. 끝 칸이면 30일 끝까지 켜진 것으로 본다
                // (그러지 않으면 한 달 내내 켜진 2vCPU 1대가 1,439.7이 되어, 같은 조건의 우리 팀 1,440보다 "적게 쓴 팀"으로 잡힌다)
                deleted = deleted >= WINDOW_SECONDS - LAST_SLOT_SECONDS ? WINDOW_SECONDS : deleted;
                long seconds = deleted - created;
                if (seconds < MIN_SECONDS) {
                    continue;
                }
                double[] acc = bySubscription.computeIfAbsent(cols[SUBSCRIPTION], k -> new double[2]);
                acc[0]++;
                acc[1] += vcpuOfBucket(cols[CORES]) * (seconds / 3600.0);
            }
        }
        List<Team> teams = new ArrayList<>();
        bySubscription.forEach((id, acc) -> {
            if (acc[0] >= MIN_SERVERS && acc[0] <= MAX_SERVERS) {
                teams.add(new Team(id, (int) acc[0], acc[1]));
            }
        });
        teams.sort((a, b) -> a.id().compareTo(b.id()));
        return teams;
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

    // ---------- 분위 회귀 ----------

    static double[][] fitAll(List<Team> teams) {
        double[][] coef = new double[PERCENTILES.length][];
        for (int i = 0; i < PERCENTILES.length; i++) {
            coef[i] = fit(teams, PERCENTILES[i] / 100.0);
        }
        return coef;
    }

    /**
     * pinball loss를 최소화하는 β (특징 [1, x, x²]). MM 알고리즘: |r|을 r²/(ε+|r_k|)로 위에서 감싸 가중 최소제곱을 반복한다.
     * 매 단계 (XᵀWX)β = XᵀWy + (2τ−1)Σx 를 푼다.
     */
    static double[] fit(List<Team> teams, double tau) {
        double[] beta = {0, 0, 0};
        double eps = 1e-6;
        for (int iter = 0; iter < 500; iter++) {
            double[][] a = new double[3][3];
            double[] b = new double[3];
            for (Team t : teams) {
                double[] f = features(t.x());
                double r = t.y() - dot(f, beta);
                double w = 1.0 / (eps + Math.abs(r));
                for (int j = 0; j < 3; j++) {
                    b[j] += w * f[j] * t.y() + (2 * tau - 1) * f[j];
                    for (int k = 0; k < 3; k++) {
                        a[j][k] += w * f[j] * f[k];
                    }
                }
            }
            double[] next = solve(a, b);
            double change = Math.abs(next[0] - beta[0]) + Math.abs(next[1] - beta[1]) + Math.abs(next[2] - beta[2]);
            beta = next;
            if (change < 1e-9) {
                break;
            }
        }
        return beta;
    }

    /** 서버 s대의 백분위별 ln(사용량) 예측. 순서가 뒤집히면 정렬해 바로잡는다 */
    static double[] predict(double[][] coef, int servers) {
        double[] f = features(Math.log(servers));
        double[] out = new double[coef.length];
        for (int i = 0; i < coef.length; i++) {
            out[i] = dot(f, coef[i]);
        }
        java.util.Arrays.sort(out);
        return out;
    }

    static double[] features(double x) {
        return new double[] {1, x, x * x};
    }

    static double pinball(double r, double tau) {
        return r >= 0 ? tau * r : (tau - 1) * r;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** 3×3 가우스 소거 (부분 피벗) */
    private static double[] solve(double[][] a, double[] b) {
        int n = 3;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(m[r][c]) > Math.abs(m[p][c])) {
                    p = r;
                }
            }
            double[] tmp = m[c];
            m[c] = m[p];
            m[p] = tmp;
            for (int r = c + 1; r < n; r++) {
                double factor = m[r][c] / m[c][c];
                for (int k = c; k <= n; k++) {
                    m[r][k] -= factor * m[c][k];
                }
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = m[r][n];
            for (int k = r + 1; k < n; k++) {
                sum -= m[r][k] * x[k];
            }
            x[r] = sum / m[r][r];
        }
        return x;
    }

    // ---------- 기존 방식 (견주기용) ----------

    private static Map<String, double[]> baselineTables(List<Team> train) {
        Map<String, double[]> tables = new HashMap<>();
        for (int[] band : BASELINE_BANDS) {
            List<Double> ys = new ArrayList<>();
            for (Team t : train) {
                if (t.servers() >= band[0] && t.servers() <= band[1]) {
                    ys.add(t.y());
                }
            }
            ys.sort(null);
            double[] table = new double[PERCENTILES.length];
            for (int i = 0; i < PERCENTILES.length; i++) {
                int rank = (int) Math.ceil(PERCENTILES[i] / 100.0 * ys.size());
                table[i] = ys.get(Math.max(rank, 1) - 1);
            }
            tables.put(band[0] + "-" + band[1], table);
        }
        return tables;
    }

    private static String bandKey(int servers) {
        for (int[] band : BASELINE_BANDS) {
            if (servers >= band[0] && servers <= band[1]) {
                return band[0] + "-" + band[1];
            }
        }
        throw new IllegalArgumentException("구간 밖: " + servers);
    }

    private static int index(int percentile) {
        for (int i = 0; i < PERCENTILES.length; i++) {
            if (PERCENTILES[i] == percentile) {
                return i;
            }
        }
        throw new IllegalArgumentException(String.valueOf(percentile));
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static BufferedReader open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (file.toString().endsWith(".gz")) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }
}
