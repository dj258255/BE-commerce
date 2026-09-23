package com.beomsu.becommerce.fraud.model;

import com.beomsu.becommerce.fraud.internal.CardBlocklist;
import com.beomsu.becommerce.fraud.internal.FdsDecision;
import com.beomsu.becommerce.fraud.internal.FraudCheckRequest;
import com.beomsu.becommerce.fraud.internal.FraudService;
import com.beomsu.becommerce.fraud.velocity.VelocityCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 규칙 · 모델 · 결합을 <b>같은 홀드아웃</b>에서 잰다.
 *
 * <p><b>이 하네스가 모델보다 먼저 있어야 한다.</b> 무엇을 맞다고 볼지 정해 두지 않으면 답이
 * 나온 뒤에 그 답에 맞춰 기준을 고치게 된다. 상황 3 에서 네 자리 전부에 같은 순서를 썼다.
 *
 * <p><b>학습과 평가를 가른다.</b> 가중치는 학습 절반에서만 뽑고, 성적은 그 절반을 한 번도
 * 안 본 홀드아웃에서 낸다. 같은 표본에서 맞추고 재면 늘 좋아 보인다.
 *
 * <p><b>지표는 업계가 부정거래 모델에 쓰는 것으로 맞췄다.</b> 재현율·정밀도·경보율을 같이 내고,
 * 임계와 무관한 비교는 <b>PR-AUC(평균 정밀도)</b> 로 한다. 정상이 압도적으로 많은 표본에서
 * ROC-AUC 는 오탐이 늘어도 거의 안 움직여 좋아 보인다. 심사 인원이 유한하다는 사실은
 * <b>상위 K 정밀도</b>로 넣는다.
 *
 * <p><b>정밀도는 기저율에 그대로 끌려간다.</b> 이 코퍼스는 부정이 30% 라 정밀도가 실제보다
 * 훨씬 높게 나온다. 그래서 재현율과 정상 오탐을 고정한 채 <b>실 기저율로 환산한 값</b>을 같이
 * 찍는다. 그 환산값이 이 모델을 켤지 정하는 근거다.
 *
 * <p><b>이 수치로 할 수 있는 말은 하나다.</b> 규칙은 건 하나만 보므로 건들 사이의 관계로 만든
 * 패턴을 못 잡는다. <b>실 트래픽에서 이런 패턴이 얼마나 되는지는 모른다.</b>
 */
@DisplayName("이상거래 탐지 — 규칙이 못 보는 축을 모델이 얼마나 잡나")
class FraudModelEvalTest {

    /** 코퍼스 seed. 고정하지 않으면 회차마다 표본이 바뀌어 비교가 안 된다. */
    private static final long SEED = 20260909L;

    private static final int NORMALS = 600;
    private static final int PER_AXIS = 40;      // 부정 여섯 축 × 40 = 240

    /** 모델을 심사로 올릴 임계. 학습 절반에서 <b>정상 오탐 5%</b> 가 되는 지점으로 잡는다. */
    private static final double TARGET_FALSE_POSITIVE = 0.05;

    // ── 규칙 쪽 ─────────────────────────────────────────────────────────────

    /**
     * 실 {@link FraudService} 를 그대로 돌린다. 규칙을 테스트에 다시 구현하면 <b>거기서
     * 규칙을 약하게 써 놓고 모델이 이겼다고 말하게 된다.</b>
     *
     * <p>속도 카운터만 가짜로 둔다. 실제 창은 Redis 1 분인데, 여기서는 <b>같은 카드·기기·IP
     * 로 1 분 안에 몇 번 왔는지</b>를 창에서 직접 세어 넘긴다. 부정 패턴이 전부 간격을 벌려
     * 놓았으므로 이 값은 대개 1 이다. <b>규칙에 유리하게 세는 쪽으로 틀리지 않았는지</b>가
     * 중요해서, 아래 {@code rulesCatchNothingByDesign} 이 그것을 따로 고정한다.
     */
    private static FraudService rules(Map<String, Integer> velocity) {
        VelocityCounter counter = mock(VelocityCounter.class);
        when(counter.recordAndCount(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> velocity.getOrDefault(inv.getArgument(0, String.class), 1));

        CardBlocklist blocklist = mock(CardBlocklist.class);
        when(blocklist.contains(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        FraudService s = new FraudService(counter, blocklist);
        ReflectionTestUtils.setField(s, "velocityThreshold", 5);
        ReflectionTestUtils.setField(s, "velocityWeight", 40);
        ReflectionTestUtils.setField(s, "amountThreshold", FraudCorpus.AMOUNT_THRESHOLD);
        ReflectionTestUtils.setField(s, "amountWeight", 30);
        ReflectionTestUtils.setField(s, "blacklistWeight", 100);
        ReflectionTestUtils.setField(s, "deviceThreshold", 8);
        ReflectionTestUtils.setField(s, "deviceWeight", 25);
        ReflectionTestUtils.setField(s, "ipThreshold", 15);
        ReflectionTestUtils.setField(s, "ipWeight", 20);
        ReflectionTestUtils.setField(s, "longInstallmentMonths", 6);
        ReflectionTestUtils.setField(s, "installmentAmountThreshold", FraudCorpus.AMOUNT_THRESHOLD);
        ReflectionTestUtils.setField(s, "installmentWeight", 20);
        ReflectionTestUtils.setField(s, "blockThreshold", 100);
        ReflectionTestUtils.setField(s, "reviewThreshold", 60);
        ReflectionTestUtils.setField(s, "challengeThreshold", 40);
        return s;
    }

    /** 1 분 창 안의 건수를 창에서 직접 센다. 실제 Redis 카운터가 세는 것과 같은 뜻이다. */
    private static Map<String, Integer> velocityOf(FraudCorpus.Case c) {
        Map<String, Integer> counts = new HashMap<>();
        var now = c.current().at();
        int card = 0, device = 0, ip = 0;
        for (TxnRecord t : c.window()) {
            if (Duration.between(t.at(), now).abs().compareTo(Duration.ofMinutes(1)) > 0) {
                continue;
            }
            card++;
            if (java.util.Objects.equals(t.deviceId(), c.current().deviceId())) device++;
            if (java.util.Objects.equals(t.ip(), c.current().ip())) ip++;
        }
        counts.put("card:" + c.cardKey(), card);
        if (c.current().deviceId() != null) counts.put("device:" + c.current().deviceId(), device);
        if (c.current().ip() != null) counts.put("ip:" + c.current().ip(), ip);
        return counts;
    }

    /** 규칙이 이 건을 사람 앞에 올리는가. {@code CHALLENGE} 위부터 사람이 본다. */
    private static boolean ruleFlags(FraudCorpus.Case c) {
        var r = rules(velocityOf(c)).evaluate(new FraudCheckRequest(
                0L, c.cardKey(), c.current().ip(), c.current().deviceId(),
                c.current().amount(), c.current().installmentMonths()));
        return r.decision() != FdsDecision.ALLOW;
    }

    @Test
    @DisplayName("경사하강이 수렴한다 — 손실이 단조로 줄고, 에폭을 열 배로 늘려도 판정이 안 바뀐다")
    void trainConverges() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        var tr = corpus.subList(0, corpus.size() / 2);
        var holdout = corpus.subList(corpus.size() / 2, corpus.size());

        var loss = new java.util.TreeMap<Integer, Double>();
        var model = train(tr, 4_000, 3.0, loss);

        System.out.printf("%n  [손실] 시작 %.6f → 끝 %.6f%n",
                loss.firstEntry().getValue(), loss.lastEntry().getValue());

        // ① 내려간다. 이걸 안 재면 <학습이 도는지>를 아무도 모른다.
        assertThat(loss.lastEntry().getValue())
                .as("손실이 안 줄면 학습률이나 기울기 계산이 틀린 것이다")
                .isLessThan(loss.firstEntry().getValue());

        // ② 오르내리지 않는다. 오르내리면 학습률이 너무 크다.
        double prev = Double.MAX_VALUE;
        for (double v : loss.values()) {
            assertThat(v).as("손실이 올라갔다. lr=3.0 이 너무 크다는 뜻이다").isLessThanOrEqualTo(prev);
            prev = v;
        }

        // ③ 평탄해졌다. 4,000 에폭이 <충분한지>에 대한 답이다.
        double tail = loss.get(3_900) - loss.get(3_999);
        System.out.printf("  마지막 100 에폭 감소분 %.8f%n", tail);
        assertThat(tail).as("아직 가파르면 에폭이 모자란 것이다").isLessThan(1e-4);

        // ④ 열 배로 돌려도 판정이 같다. 정규화가 없어 가중치는 계속 자라지만,
        //    이 모델은 <순서를 정하는 데만> 쓰므로 판정이 안 바뀌면 그것으로 충분하다.
        var longer = train(tr, 40_000, 3.0);
        double th = thresholdAt(model, tr);
        double thLong = thresholdAt(longer, tr);
        long flipped = holdout.stream().filter(c ->
                (model.risk(featuresOf(c)) >= th) != (longer.risk(featuresOf(c)) >= thLong)).count();
        System.out.printf("  4,000 vs 40,000 에폭 · 홀드아웃 %d건 중 판정이 갈린 건 %d%n",
                holdout.size(), flipped);
        assertThat(flipped).as("에폭을 늘려 판정이 바뀌면 4,000 에폭에서 뽑은 가중치를 쓸 수 없다")
                .isZero();
    }

    @Test
    @DisplayName("서빙 가중치가 지금 학습 결과와 같다 — 손으로 옮기다 어긋나는 자리다")
    void servedWeightsMatchTraining() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        var model = train(corpus.subList(0, corpus.size() / 2), 4_000, 3.0);

        assertWeights("application.yml", ymlDefault("weights"), ymlDefault("bias"), model);
        assertWeights("FraudModelConfig", annotationDefault("weights"), annotationDefault("bias"), model);

        // 임계도 학습이 정한다. 가중치만 옮기고 임계를 두면 오탐 5% 라는 전제가 깨진다.
        double trained = thresholdAt(model, corpus.subList(0, corpus.size() / 2));
        for (var src : java.util.Map.of(
                "application.yml", ymlDefault("threshold"),
                "LabelledScoreReport", labelledReportThresholdDefault()).entrySet()) {
            assertThat(Double.parseDouble(src.getValue().trim()))
                    .as("%s 의 임계가 학습 결과와 다르다", src.getKey())
                    .isCloseTo(trained, within(5e-6));
        }
    }

    /** {@code LabelledScoreReport} 의 {@code @Value} 임계 기본값. */
    private static String labelledReportThresholdDefault() {
        try {
            var v = LabelledScoreReport.class.getDeclaredField("threshold")
                    .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
            return v.substring(v.indexOf(':') + 1, v.length() - 1);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertWeights(String where, String weightCsv, String biasText,
                                      LogisticFraudRiskModel trained) {
        double[] served = java.util.Arrays.stream(weightCsv.split(","))
                .map(String::trim).mapToDouble(Double::parseDouble).toArray();
        assertThat(served)
                .as("%s 의 가중치 개수가 피처 개수와 다르다", where)
                .hasSize(SequenceFeatures.NAMES.size());
        for (int i = 0; i < served.length; i++) {
            assertThat(served[i])
                    .as("%s 의 %s 가중치가 학습 결과와 다르다. 하네스가 찍어 준 값을 옮기지 않았다",
                            where, SequenceFeatures.NAMES.get(i))
                    .isCloseTo(trained.weights()[i], within(5e-6));
        }
        assertThat(Double.parseDouble(biasText.trim()))
                .as("%s 의 절편이 학습 결과와 다르다", where)
                .isCloseTo(trained.bias(), within(5e-6));
    }

    /** {@code application.yml} 의 {@code fds.model.<key>} 기본값. 실제로 서빙에 쓰이는 값이다. */
    private static String ymlDefault(String key) {
        try (var in = FraudModelEvalTest.class.getResourceAsStream("/application.yml")) {
            String yml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern
                    .compile("^\\s*" + key + ":\\s*\\$\\{[A-Z_]+:([^}]*)}", java.util.regex.Pattern.MULTILINE)
                    .matcher(yml);
            assertThat(m.find()).as("application.yml 에서 fds.model.%s 를 못 찾았다", key).isTrue();
            return m.group(1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code FraudModelConfig} 의 {@code @Value} 기본값. yml 이 없을 때 쓰이는 값이다. */
    private static String annotationDefault(String field) {
        try {
            var v = FraudModelConfig.class.getDeclaredField(field)
                    .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
            return v.substring(v.indexOf(':') + 1, v.length() - 1);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("피처 행렬을 내보낸다 — 파이썬 학습기가 이 파일만 읽는다")
    void exportsFeatureMatrix() throws java.io.IOException {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;

        java.nio.file.Path dir = java.nio.file.Path.of("build", "fds");
        java.nio.file.Files.createDirectories(dir);
        writeMatrix(dir.resolve("train.csv"), corpus.subList(0, cut));
        writeMatrix(dir.resolve("holdout.csv"), corpus.subList(cut, corpus.size()));

        System.out.printf("%n  [피처 행렬] %s · 학습 %d행 · 홀드아웃 %d행 · 열 %d + label%n",
                dir.toAbsolutePath(), cut, corpus.size() - cut, SequenceFeatures.NAMES.size());

        assertThat(java.nio.file.Files.readAllLines(dir.resolve("train.csv")))
                .as("헤더 한 줄 + 학습 표본 수만큼")
                .hasSize(cut + 1);
    }

    /**
     * <b>피처는 여기서만 계산한다.</b> 파이썬이 {@link SequenceFeatures} 를 다시 구현하면
     * 학습과 서빙이 다른 값을 보게 되고, 그 어긋남은 점수가 이상해질 때까지 안 보인다.
     * 이 저장소는 이미 그 실패를 겪었다(기기·IP 가 실행 경로에 안 실려 오던 것).
     */
    private static void writeMatrix(java.nio.file.Path out, List<FraudCorpus.Case> cases)
            throws java.io.IOException {
        var sb = new StringBuilder(String.join(",", SequenceFeatures.NAMES)).append(",label\n");
        for (var c : cases) {
            for (double v : featuresOf(c).toArray()) sb.append("%.10f".formatted(v)).append(',');
            sb.append(c.fraud() ? 1 : 0).append('\n');
        }
        java.nio.file.Files.writeString(out, sb.toString());
    }

    @Test
    @DisplayName("손으로 짠 학습기가 scikit-learn 과 같은 곳에 도착한다")
    void agreesWithSklearn() throws java.io.IOException {
        java.nio.file.Path artifact = java.nio.file.Path.of("build", "fds", "sklearn-model.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(artifact),
                "tools/fds/train.py 를 안 돌렸다. 파이썬 없이도 나머지 평가는 돌아야 한다");

        String json = java.nio.file.Files.readString(artifact);
        double[] skW = parseArray(json, "weights");
        double skB = parseScalar(json, "bias");
        double skLoss = parseScalar(json, "trainLogLoss");

        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        var tr = corpus.subList(0, cut);
        var holdout = corpus.subList(cut, corpus.size());
        var mine = train(tr, 4_000, 3.0);
        var sk = new LogisticFraudRiskModel(skW, skB);

        System.out.printf("%n  %-20s %12s %12s %10s%n", "피처", "손수", "sklearn", "차이");
        for (int i = 0; i < skW.length; i++) {
            System.out.printf("  %-18s %12.6f %12.6f %10.6f%n", SequenceFeatures.NAMES.get(i),
                    mine.weights()[i], skW[i], Math.abs(mine.weights()[i] - skW[i]));
        }
        System.out.printf("  %-18s %12.6f %12.6f %10.6f%n", "bias", mine.bias(), skB,
                Math.abs(mine.bias() - skB));

        // ① 학습 손실이 거의 같다. 손수 짠 경사하강이 sklearn 이 찾은 최적점 근처에 있다는 뜻이다.
        var xs = tr.stream().map(c -> featuresOf(c).toArray()).toList();
        var ys = tr.stream().map(c -> c.fraud() ? 1.0 : 0.0).toList();
        double myLoss = logLoss(mine.weights(), mine.bias(), xs, ys);
        System.out.printf("%n  학습 손실  손수 %.6f · sklearn %.6f · 차이 %.6f%n",
                myLoss, skLoss, Math.abs(myLoss - skLoss));
        assertThat(myLoss).as("손실이 벌어지면 손수 짠 학습기가 최적점에 못 갔다는 뜻이다")
                .isCloseTo(skLoss, within(1e-3));

        // ② 홀드아웃 판정이 같다. 심사 순서를 정하는 데 쓰므로 실제로 같아야 하는 것은 이쪽이다.
        double thMine = thresholdAt(mine, tr);
        double thSk = thresholdAt(sk, tr);
        long flipped = holdout.stream().filter(c ->
                (mine.risk(featuresOf(c)) >= thMine) != (sk.risk(featuresOf(c)) >= thSk)).count();
        System.out.printf("  홀드아웃 %d건 중 판정이 갈린 건 %d%n", holdout.size(), flipped);
        assertThat(flipped).as("두 학습기가 다른 판정을 내면 어느 쪽을 서빙할지 정할 근거가 없다")
                .isZero();

        // ③ 상위 K 가 같다. 이 모델이 실제로 하는 일이 <위험한 순서로 줄 세우기> 다.
        assertThat(topK(mine, holdout, 20))
                .as("상위 20 집합이 다르면 심사자가 보는 목록이 달라진다")
                .containsExactlyInAnyOrderElementsOf(topK(sk, holdout, 20));
    }

    /** 위험한 순서로 줄 세운 상위 {@code k} 의 <b>표본 번호</b>. 코퍼스에 식별자가 없어 색인을 쓴다. */
    private static List<Integer> topK(LogisticFraudRiskModel m, List<FraudCorpus.Case> cases, int k) {
        return java.util.stream.IntStream.range(0, cases.size()).boxed()
                .sorted((a, b) -> Double.compare(
                        m.risk(featuresOf(cases.get(b))), m.risk(featuresOf(cases.get(a)))))
                .limit(k).toList();
    }

    private static double[] parseArray(String json, String key) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        assertThat(m.find()).as("%s 를 못 찾았다", key).isTrue();
        return java.util.Arrays.stream(m.group(1).split(",")).map(String::trim)
                .mapToDouble(Double::parseDouble).toArray();
    }

    private static double parseScalar(String json, String key) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[\\d.eE+-]+)").matcher(json);
        assertThat(m.find()).as("%s 를 못 찾았다", key).isTrue();
        return Double.parseDouble(m.group(1));
    }

    // ── 학습 ────────────────────────────────────────────────────────────────

    /**
     * 경사하강법. <b>라이브러리를 안 쓴다</b> — 여덟 개 피처에 로지스틱 하나라 스무 줄이면 되고,
     * 의존을 늘리면 CI 에서 이 평가가 도는 조건이 하나 더 생긴다.
     */
    private static LogisticFraudRiskModel train(List<FraudCorpus.Case> cases, int epochs, double lr) {
        return train(cases, epochs, lr, null);
    }

    /**
     * 경사하강법. 위 오버로드와 <b>계산은 같고</b>, {@code lossAt} 을 주면 100 에폭마다
     * 로그 손실을 적어 둔다.
     *
     * <p><b>손실은 기록할 때만 계산한다.</b> 매 에폭 재면 코퍼스를 한 번 더 훑어 학습이
     * 두 배로 느려지고, 그 비용은 수렴을 확인하는 테스트에서만 낼 값이다.
     */
    private static LogisticFraudRiskModel train(List<FraudCorpus.Case> cases, int epochs, double lr,
                                                Map<Integer, Double> lossAt) {
        int dim = SequenceFeatures.NAMES.size();
        double[] w = new double[dim];
        double b = 0;

        List<double[]> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (var c : cases) {
            xs.add(featuresOf(c).toArray());
            ys.add(c.fraud() ? 1.0 : 0.0);
        }

        for (int e = 0; e < epochs; e++) {
            double[] gw = new double[dim];
            double gb = 0;
            for (int i = 0; i < xs.size(); i++) {
                double[] x = xs.get(i);
                double z = b;
                for (int j = 0; j < dim; j++) z += w[j] * x[j];
                double p = 1.0 / (1.0 + Math.exp(-z));
                double err = p - ys.get(i);
                for (int j = 0; j < dim; j++) gw[j] += err * x[j];
                gb += err;
            }
            for (int j = 0; j < dim; j++) w[j] -= lr * gw[j] / xs.size();
            b -= lr * gb / xs.size();

            if (lossAt != null && (e % 100 == 0 || e == epochs - 1)) {
                lossAt.put(e, logLoss(w, b, xs, ys));
            }
        }
        return new LogisticFraudRiskModel(w, b);
    }

    /** 평균 로그 손실. 경사하강이 실제로 내려가고 있는지 보는 유일한 값이다. */
    private static double logLoss(double[] w, double b, List<double[]> xs, List<Double> ys) {
        double sum = 0;
        for (int i = 0; i < xs.size(); i++) {
            double[] x = xs.get(i);
            double z = b;
            for (int j = 0; j < w.length; j++) z += w[j] * x[j];
            double p = 1.0 / (1.0 + Math.exp(-z));
            double eps = 1e-12;
            sum += -(ys.get(i) * Math.log(p + eps) + (1 - ys.get(i)) * Math.log(1 - p + eps));
        }
        return sum / xs.size();
    }

    private static SequenceFeatures featuresOf(FraudCorpus.Case c) {
        return SequenceFeatures.of(c.window(), c.current(), FraudCorpus.AMOUNT_THRESHOLD);
    }

    /** 학습 절반에서 정상 오탐이 {@code TARGET_FALSE_POSITIVE} 가 되는 임계를 고른다. */
    private static double thresholdAt(LogisticFraudRiskModel model, List<FraudCorpus.Case> train) {
        List<Double> normalScores = train.stream().filter(c -> !c.fraud())
                .map(c -> model.risk(featuresOf(c))).sorted().toList();
        if (normalScores.isEmpty()) {
            return 0.5;
        }
        int idx = (int) Math.floor(normalScores.size() * (1 - TARGET_FALSE_POSITIVE));
        return normalScores.get(Math.min(idx, normalScores.size() - 1));
    }

    // ── 채점 ────────────────────────────────────────────────────────────────

    /**
     * 한 방식의 성적.
     *
     * <p>지표를 셋으로 두는 것은 업계 관행을 따른 것이다. 재현율만 보면 전부 올리면 100% 가
     * 되고, 정밀도만 보면 확실한 것만 올려 대부분을 놓친다. 그리고 <b>경보율</b>이 있어야
     * 심사 인원이 감당할 양인지 알 수 있다.
     */
    private record Score(String name, long caught, long missed, long falseAlarm, long normals) {
        /** 부정 중 잡은 비율. */
        double recall() { return caught + missed == 0 ? 0 : (double) caught / (caught + missed); }
        /** 정상 중 잘못 올린 비율. */
        double falseAlarmRate() { return normals == 0 ? 0 : (double) falseAlarm / normals; }
        /** 올린 것 중 진짜 부정의 비율. <b>심사자가 겪는 체감이 이 값이다.</b> */
        double precision() { return caught + falseAlarm == 0 ? 0 : (double) caught / (caught + falseAlarm); }
        /** 전체 중 심사 큐로 올라가는 비율. 사람이 감당할 양인지를 정하는 값이다. */
        double alertRate() {
            long total = caught + missed + normals;
            return total == 0 ? 0 : (double) (caught + falseAlarm) / total;
        }
    }

    private static Score score(String name, List<FraudCorpus.Case> holdout,
                               java.util.function.Predicate<FraudCorpus.Case> flags) {
        long caught = 0, missed = 0, falseAlarm = 0, normals = 0;
        for (var c : holdout) {
            boolean f = flags.test(c);
            if (c.fraud()) {
                if (f) caught++; else missed++;
            } else {
                normals++;
                if (f) falseAlarm++;
            }
        }
        return new Score(name, caught, missed, falseAlarm, normals);
    }

    // ── 임계와 무관한 지표 ──────────────────────────────────────────────────

    /**
     * 평균 정밀도(PR 곡선 아래 넓이).
     *
     * <p><b>불균형 표본에서는 ROC-AUC 보다 이쪽을 본다.</b> ROC 는 위음성률을 쓰는데, 정상이
     * 압도적으로 많으면 오탐이 조금 늘어도 그 비율이 거의 안 움직여 곡선이 좋아 보인다.
     * PR 곡선은 정밀도를 쓰므로 정상 개수에 덜 휘둘린다. 업계가 부정거래 모델을 이 지표로
     * 재는 이유가 그것이다.
     *
     * <p><b>임계를 안 정하고 재는 것이 요점이다.</b> 임계를 고르고 나서 비교하면 그 임계를
     * 어느 쪽에 유리하게 잡았는지가 결과에 섞인다.
     */
    private static double averagePrecision(List<FraudCorpus.Case> holdout,
                                           java.util.function.ToDoubleFunction<FraudCorpus.Case> score) {
        var ranked = holdout.stream()
                .sorted(java.util.Comparator.comparingDouble(score).reversed())
                .toList();
        long positives = ranked.stream().filter(FraudCorpus.Case::fraud).count();
        if (positives == 0) {
            return 0;
        }
        long tp = 0;
        double sum = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).fraud()) {
                tp++;
                sum += (double) tp / (i + 1);   // 이 건을 잡은 시점의 정밀도
            }
        }
        return sum / positives;
    }

    /**
     * 상위 {@code k} 건 안의 정밀도.
     *
     * <p><b>심사 인원이 유한하다</b>는 사실을 지표에 넣는 것이다. 하루에 30건을 볼 수 있으면
     * 점수 상위 30건만 보게 되고, 그 30건 중 몇 건이 진짜인지가 그 팀이 겪는 값이다.
     * 전체 재현율이 좋아도 상위 구간이 정상으로 차 있으면 현장에서는 쓸모가 없다.
     */
    private static double precisionAt(List<FraudCorpus.Case> holdout, int k,
                                      java.util.function.ToDoubleFunction<FraudCorpus.Case> score) {
        var top = holdout.stream()
                .sorted(java.util.Comparator.comparingDouble(score).reversed())
                .limit(k).toList();
        return top.isEmpty() ? 0 : (double) top.stream().filter(FraudCorpus.Case::fraud).count() / top.size();
    }

    // ── 시험 ────────────────────────────────────────────────────────────────

    /** 규칙을 피해 가게 만든 여섯 축. 규칙이 잡으라고 만든 두 축은 뺀다. */
    private static final java.util.Set<String> EVASIVE_AXES = java.util.Set.of(
            "structuring", "card_testing", "escalation",
            "device_rotation", "ip_rotation", "night_burst");

    @Test
    @DisplayName("규칙은 피해 가게 만든 여섯 축을 거의 못 잡는다 — 그래서 모델을 붙일 자리가 있다")
    void rulesCatchNothingByDesign() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        var frauds = corpus.stream()
                .filter(FraudCorpus.Case::fraud)
                .filter(c -> EVASIVE_AXES.contains(c.axis()))
                .toList();

        long caught = frauds.stream().filter(FraudModelEvalTest::ruleFlags).count();
        double recall = (double) caught / frauds.size();

        System.out.printf("%n  [규칙만] 부정 %d건 중 %d건 탐지 (재현율 %.1f%%)%n",
                frauds.size(), caught, recall * 100);

        // 코퍼스를 규칙이 못 잡게 짠 것이 실제로 그런지 고정한다. 여기가 깨지면
        // 코퍼스가 규칙이 잡는 패턴으로 흘러간 것이고, 그때 모델 성적은 의미가 없다.
        assertThat(recall)
                .as("규칙이 이미 잡는 패턴이면 모델이 규칙을 다시 배우는 것을 재게 된다")
                .isLessThan(0.15);
    }

    @Test
    @DisplayName("규칙 · 모델 · 결합을 같은 홀드아웃에서 잰다")
    void compareOnHoldout() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        var train = corpus.subList(0, cut);
        var holdout = corpus.subList(cut, corpus.size());

        var model = train(train, 4_000, 3.0);
        double threshold = thresholdAt(model, train);

        // 운영에 넣을 값을 그대로 찍는다. 손으로 옮겨 적다 순서가 어긋나면 조용히 틀린 점수가 난다.
        System.out.printf("%n  [application.yml 에 넣을 값]%n    weights: %s%n    bias: %.6f%n    threshold: %.6f%n",
                java.util.Arrays.stream(model.weights())
                        .mapToObj("%.6f"::formatted).collect(java.util.stream.Collectors.joining(", ")),
                model.bias(), threshold);

        java.util.function.Predicate<FraudCorpus.Case> byModel =
                c -> model.risk(featuresOf(c)) >= threshold;

        var results = List.of(
                score("규칙만", holdout, FraudModelEvalTest::ruleFlags),
                score("모델만", holdout, byModel),
                score("규칙 또는 모델", holdout, c -> ruleFlags(c) || byModel.test(c)));

        System.out.printf("%n  학습 %d건 · 홀드아웃 %d건 (부정 %d건) · 모델 임계 %.3f%n",
                train.size(), holdout.size(),
                holdout.stream().filter(FraudCorpus.Case::fraud).count(), threshold);
        System.out.printf("  %-14s %8s %8s %8s %8s%n", "", "재현율", "정밀도", "정상오탐", "경보율");
        for (var s : results) {
            System.out.printf("  %-14s %7.1f%% %7.1f%% %7.1f%% %7.1f%%   (탐지 %d · 놓침 %d · 오탐 %d)%n",
                    s.name(), s.recall() * 100, s.precision() * 100,
                    s.falseAlarmRate() * 100, s.alertRate() * 100,
                    s.caught(), s.missed(), s.falseAlarm());
        }

        // 임계와 무관한 지표. 임계를 어느 쪽에 유리하게 잡았는지가 안 섞인다.
        double apModel = averagePrecision(holdout, c -> model.risk(featuresOf(c)));
        double apRule = averagePrecision(holdout, c -> ruleFlags(c) ? 1 : 0);
        System.out.printf("%n  평균 정밀도(PR-AUC)   규칙 %.3f · 모델 %.3f%n", apRule, apModel);
        System.out.printf("  상위 K 정밀도(모델)   ");
        for (int k : new int[]{10, 30, 60}) {
            System.out.printf("P@%d %.0f%%  ", k, precisionAt(holdout, k, c -> model.risk(featuresOf(c))) * 100);
        }
        System.out.println();

        // 이 코퍼스의 부정 비율은 실제와 다르다. 정밀도는 기저율에 그대로 끌려가므로
        // 여기 91.7% 를 그대로 인용하면 과장이 된다. 환산해서 같이 적는다.
        var m0 = results.get(1);
        double base = (double) holdout.stream().filter(FraudCorpus.Case::fraud).count() / holdout.size();
        System.out.printf("%n  기저율 환산 (재현율 %.3f · 정상오탐 %.3f 고정)%n", m0.recall(), m0.falseAlarmRate());
        System.out.printf("    이 코퍼스 %.1f%% -> 정밀도 %.1f%%%n", base * 100, m0.precision() * 100);
        for (double p : new double[]{0.01, 0.001}) {
            double prec = p * m0.recall() / (p * m0.recall() + (1 - p) * m0.falseAlarmRate());
            System.out.printf("    실 기저율 %.1f%% -> 정밀도 %.1f%%  (심사 %d건에 진짜 %d건)%n",
                    p * 100, prec * 100, 100, Math.round(prec * 100));
        }

        assertThat(apModel)
                .as("불균형 표본이라 PR-AUC 로 본다. 임계를 안 정하고도 모델이 규칙보다 나아야 한다")
                .isGreaterThan(apRule);
        assertThat(precisionAt(holdout, 30, c -> model.risk(featuresOf(c))))
                .as("심사 인원이 유한하다. 상위 30건이 정상으로 차 있으면 현장에서는 못 쓴다")
                .isGreaterThan(0.5);

        var rule = results.get(0);
        var m = results.get(1);
        var combined = results.get(2);

        assertThat(m.recall())
                .as("규칙이 못 보는 축으로 만든 패턴이라 모델이 더 잡아야 한다")
                .isGreaterThan(rule.recall());
        assertThat(combined.recall())
                .as("결합은 어느 한쪽보다 덜 잡을 수 없다")
                .isGreaterThanOrEqualTo(m.recall());
        assertThat(m.falseAlarmRate())
                .as("임계를 학습 절반의 정상 오탐 5%%에 맞췄다. 홀드아웃에서 크게 벌어지면 과적합이다")
                .isLessThan(0.15);
    }

    /**
     * <b>손으로 쓴 시간창 규칙.</b> 모델을 고른 이유를 대려면 이것으로 안 되는지를 먼저 봐야 한다.
     *
     * <p>지금 규칙 여섯이 못 보는 것은 "긴 창"이라는 축이다. 그러면 창만 늘린 규칙 몇 개로
     * 되는지가 먼저 나올 질문이다. 여기서 만든 것은 사람이 눈으로 짤 만한 수준의 조건이다.
     */
    private static boolean windowRuleFlags(FraudCorpus.Case c) {
        var w = c.window();
        long micro = w.stream().filter(t -> t.amount() <= SequenceFeatures.MICRO_AMOUNT).count();
        long near = w.stream()
                .filter(t -> t.amount() >= FraudCorpus.AMOUNT_THRESHOLD * 9 / 10
                        && t.amount() < FraudCorpus.AMOUNT_THRESHOLD).count();
        long distinctDevices = w.stream().map(TxnRecord::deviceId)
                .filter(java.util.Objects::nonNull).distinct().count();
        return micro >= 3            // 소액을 세 번 이상 떠봤다
                || near >= 3         // 임계 바로 밑을 세 번 이상 밀었다
                || distinctDevices >= 5;   // 기기를 다섯 개 이상 썼다
    }

    @Test
    @DisplayName("손으로 쓴 시간창 규칙과 견준다 — 모델을 고른 이유를 대려면 이게 먼저다")
    void comparedAgainstAHandWrittenWindowRule() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        var model = train(corpus.subList(0, cut), 4_000, 3.0);
        double threshold = thresholdAt(model, corpus.subList(0, cut));
        var holdout = corpus.subList(cut, corpus.size());

        var results = List.of(
                score("규칙 여섯", holdout, FraudModelEvalTest::ruleFlags),
                score("시간창 규칙", holdout, FraudModelEvalTest::windowRuleFlags),
                score("모델", holdout, c -> model.risk(featuresOf(c)) >= threshold));

        System.out.printf("%n  [손으로 쓴 시간창 규칙과 견주기]%n");
        System.out.printf("  %-12s %8s %8s %10s%n", "", "재현율", "정밀도", "정상오탐");
        for (var s : results) {
            System.out.printf("  %-12s %7.1f%% %7.1f%% %8.1f%%%n",
                    s.name(), s.recall() * 100, s.precision() * 100, s.falseAlarmRate() * 100);
        }

        var windowRule = results.get(1);
        var m = results.get(2);
        System.out.printf("  축별 (시간창 규칙 / 모델)%n");
        for (String axis : List.of("structuring", "card_testing", "escalation",
                                   "device_rotation", "ip_rotation", "night_burst")) {
            var axisCases = holdout.stream().filter(c -> c.axis().equals(axis)).toList();
            long byRule = axisCases.stream().filter(FraudModelEvalTest::windowRuleFlags).count();
            long byModel = axisCases.stream()
                    .filter(c -> model.risk(featuresOf(c)) >= threshold).count();
            System.out.printf("    %-16s %2d/%2d  /  %2d/%2d%n",
                    axis, byRule, axisCases.size(), byModel, axisCases.size());
        }

        // 이 시험은 어느 쪽이 이기는지를 고정하지 않는다. <b>둘을 나란히 적는 것</b>이 목적이다.
        // 시간창 규칙이 더 나으면 그건 모델을 끄라는 뜻이고, 그 판단도 숫자로 해야 한다.
        assertThat(windowRule.recall()).as("이 규칙이 아무것도 못 잡으면 비교가 안 된다").isGreaterThan(0.0);
        assertThat(m.recall()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("규칙이 올린 큐 안에서 순서를 비교한다 — 런타임과 같은 여섯 피처로 잰다")
    void rankingInsideTheRuleFlaggedQueue() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        // <b>런타임에 실제로 도는 조건으로 잰다.</b> 여덟 피처 모델로 재면 운영에서 못 내는
        // 성적을 정렬의 근거로 쓰게 된다. 기기·IP 신호를 지우고 학습한 모델을 쓴다.
        var trainRt = corpus.subList(0, cut).stream()
                .map(FraudModelEvalTest::withoutRequestSignals).toList();
        var model = train(trainRt, 4_000, 3.0);
        var holdout = corpus.subList(cut, corpus.size()).stream()
                .map(FraudModelEvalTest::withoutRequestSignals).toList();

        // <b>여기가 정렬을 켤 근거가 나오는 자리다.</b> 전체 표본에서 잰 P@K 는 규칙이
        // 올리지도 않은 건들을 포함해서, 큐 정렬이 나은지에 대해 아무 말도 못 한다.
        var queue = holdout.stream().filter(FraudModelEvalTest::ruleFlags).toList();
        long fraudInQueue = queue.stream().filter(FraudCorpus.Case::fraud).count();

        System.out.printf("%n  [큐 정렬] 규칙이 올린 %d건 중 부정 %d건 (%.1f%%)%n",
                queue.size(), fraudInQueue, 100.0 * fraudInQueue / queue.size());

        assertThat(queue).as("큐가 비면 정렬을 잴 수 없다").isNotEmpty();
        assertThat(fraudInQueue).as("큐에 오탐이 섞여야 정렬에 의미가 있다")
                .isLessThan(queue.size());

        // 기존 순서: 최근 것부터. 지금 화면의 기본 정렬(id DESC)과 같은 뜻이다.
        var byRecency = queue.stream()
                .sorted(java.util.Comparator.comparing(
                        (FraudCorpus.Case c) -> c.current().at()).reversed())
                .toList();
        var byModel = queue.stream()
                .sorted(java.util.Comparator.comparingDouble(
                        (FraudCorpus.Case c) -> model.risk(featuresOf(c))).reversed())
                .toList();

        // <b>무작위는 한 번 섞은 값을 쓰면 안 된다.</b> 처음에 그렇게 해서 P@10 이 10% 로
        // 나왔는데, 균등 무작위의 기댓값은 큐의 부정 비율(38%)이다. 한 번의 결과를 그 방식의
        // 대표 성능으로 쓰면 모델과의 차이가 과장된다. 200회 섞어 평균을 낸다.
        System.out.printf("  %-14s %8s %8s %8s%n", "", "P@5", "P@10", "P@20");
        System.out.printf("  %-14s", "무작위(200회 평균)");
        for (int k : new int[]{5, 10, 20}) {
            System.out.printf(" %7.1f%%", averageRandomPrecision(queue, k, 200) * 100);
        }
        System.out.printf("   기댓값 %.1f%%%n", 100.0 * fraudInQueue / queue.size());

        for (var pair : List.of(java.util.Map.entry("기존(최신순)", byRecency),
                                java.util.Map.entry("모델 점수순", byModel))) {
            System.out.printf("  %-14s", pair.getKey());
            for (int k : new int[]{5, 10, 20}) {
                System.out.printf(" %7.1f%%", precisionInOrder(pair.getValue(), k) * 100);
            }
            System.out.println();
        }

        double modelAt10 = precisionInOrder(byModel, 10);
        double recencyAt10 = precisionInOrder(byRecency, 10);
        System.out.printf("  큐 부정 비율 %.1f%% 대비 모델 상위 10건 %.1f%%%n",
                100.0 * fraudInQueue / queue.size(), modelAt10 * 100);

        assertThat(modelAt10)
                .as("큐 안에서 모델 순서가 기존 순서보다 나아야 정렬을 켤 근거가 된다")
                .isGreaterThan(recencyAt10);
        assertThat(modelAt10)
                .as("무작위의 기댓값은 큐의 부정 비율이다. 그것보다 나아야 정렬이 일을 한 것이다")
                .isGreaterThan((double) fraudInQueue / queue.size());
        assertThat(averageRandomPrecision(queue, 10, 200))
                .as("무작위 평균은 큐의 부정 비율 근처로 수렴해야 한다")
                .isCloseTo((double) fraudInQueue / queue.size(), org.assertj.core.data.Offset.offset(0.08));
    }

    /**
     * 무작위 순서의 상위 {@code k} 정밀도를 {@code trials} 회 평균한다.
     *
     * <p>한 번 섞은 값은 표본 하나다. 그 방식의 대표 성능으로 쓰면 비교가 과장된다.
     * 평균은 큐의 부정 비율로 수렴하므로 그것이 진짜 기준선이다.
     */
    private static double averageRandomPrecision(List<FraudCorpus.Case> queue, int k, int trials) {
        var rng = new java.util.Random(SEED);
        var pool = new ArrayList<>(queue);
        double sum = 0;
        for (int t = 0; t < trials; t++) {
            java.util.Collections.shuffle(pool, rng);
            sum += precisionInOrder(pool, k);
        }
        return sum / trials;
    }

    /** 이미 정렬된 목록의 상위 {@code k} 건 정밀도. */
    private static double precisionInOrder(List<FraudCorpus.Case> ordered, int k) {
        var top = ordered.stream().limit(k).toList();
        return top.isEmpty() ? 0 : (double) top.stream().filter(FraudCorpus.Case::fraud).count() / top.size();
    }

    @Test
    @DisplayName("런타임 조건으로 다시 잰다 — 기기·IP 신호가 없으면 피처 여덟 중 여섯이다")
    void runtimeConditionWithoutRequestSignals() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        var train = corpus.subList(0, cut);
        var holdout = corpus.subList(cut, corpus.size());

        // 결제 완료 이벤트가 Zero-Payload 라 런타임에는 ip·deviceId 가 없다.
        // 창을 그대로 두고 그 두 값만 지워 <b>같은 조건으로 다시 학습하고 다시 잰다.</b>
        var trainRt = train.stream().map(FraudModelEvalTest::withoutRequestSignals).toList();
        var holdoutRt = holdout.stream().map(FraudModelEvalTest::withoutRequestSignals).toList();

        var full = train(train, 4_000, 3.0);
        var runtime = train(trainRt, 4_000, 3.0);
        double thFull = thresholdAt(full, train);
        double thRt = thresholdAt(runtime, trainRt);

        var sFull = score("피처 여덟", holdout, c -> full.risk(featuresOf(c)) >= thFull);
        var sRt = score("피처 여섯", holdoutRt, c -> runtime.risk(featuresOf(c)) >= thRt);

        System.out.printf("%n  [런타임 조건] 기기·IP 신호를 지우고 다시 학습·측정%n");
        for (var s : List.of(sFull, sRt)) {
            System.out.printf("    %-10s 재현율 %5.1f%% · 정밀도 %5.1f%% · 정상오탐 %4.1f%%%n",
                    s.name(), s.recall() * 100, s.precision() * 100, s.falseAlarmRate() * 100);
        }

        assertThat(sRt.recall())
                .as("여섯 개 조건에서도 규칙(재현율 0)보다는 나아야 한다")
                .isGreaterThan(0.0);
    }

    /** 요청 시점 신호를 지운 사본. 창의 시각과 금액은 그대로 둔다. */
    private static FraudCorpus.Case withoutRequestSignals(FraudCorpus.Case c) {
        var window = c.window().stream()
                .map(t -> new TxnRecord(t.amount(), t.at(), null, null, t.installmentMonths()))
                .toList();
        var current = new TxnRecord(c.current().amount(), c.current().at(), null, null,
                c.current().installmentMonths());
        return new FraudCorpus.Case(c.cardKey(), window, current, c.fraud(), c.axis());
    }

    @Test
    @DisplayName("어느 축을 잡고 어느 축을 놓치는지 갈라 본다 — 전체 재현율 하나로는 못 고른다")
    void perAxisRecall() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        var model = train(corpus.subList(0, cut), 4_000, 3.0);
        double threshold = thresholdAt(model, corpus.subList(0, cut));
        var holdout = corpus.subList(cut, corpus.size());

        Map<String, long[]> byAxis = new LinkedHashMap<>();   // [탐지, 전체]
        for (var c : holdout) {
            if (!c.fraud()) continue;
            long[] t = byAxis.computeIfAbsent(c.axis(), k -> new long[2]);
            if (model.risk(featuresOf(c)) >= threshold) t[0]++;
            t[1]++;
        }

        System.out.printf("%n  [모델] 축별 재현율%n");
        byAxis.forEach((axis, t) -> System.out.printf("    %-16s %2d/%2d (%.0f%%)%n",
                axis, t[0], t[1], 100.0 * t[0] / t[1]));

        assertThat(byAxis).as("여덟 축이 홀드아웃에 다 있어야 한다").hasSize(8);
    }

    @Test
    @DisplayName("어느 피처가 점수를 올렸는지 사람이 대조할 수 있어야 한다")
    void contributorsAreReadable() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        var model = train(corpus.subList(0, corpus.size() / 2), 4_000, 3.0);

        var testing = corpus.stream().filter(c -> c.axis().equals("card_testing")).findFirst().orElseThrow();
        String[] top = model.topContributors(featuresOf(testing), 3);

        System.out.printf("%n  카드 테스팅 건의 상위 기여 피처: %s%n", String.join(", ", top));
        assertThat(top).hasSize(3);
        // 모델이 낸 점수만 주면 심사자가 확인할 방법이 없다. 피처 이름이 함께 나가야 한다.
        assertThat(String.join(",", top))
                .containsAnyOf(SequenceFeatures.NAMES.toArray(String[]::new));
    }
}
