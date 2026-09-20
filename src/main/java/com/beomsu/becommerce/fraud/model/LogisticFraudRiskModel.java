package com.beomsu.becommerce.fraud.model;

/**
 * 로지스틱 회귀. <b>가중치는 학습으로 정하고 여기서는 곱하기만 한다.</b>
 *
 * <p><b>왜 이 모델인가.</b> 세 가지가 필요했다.
 * <ul>
 *   <li><b>결정적</b> — 같은 입력에 같은 점수. 평가를 두 번 돌려 다르면 어느 쪽을 믿을지 모른다.
 *       상황 6 에서 표본 500 으로 잰 값이 회차마다 뒤집힌 것을 겪었다</li>
 *   <li><b>읽히는 근거</b> — 가중치 하나가 피처 하나에 붙는다. 어느 피처가 점수를 올렸는지
 *       심사자에게 그대로 보여줄 수 있다. 상황 5.1 에서 정한 것과 같다.
 *       사람이 판단하는 자리에는 근거가 같이 가야 한다</li>
 *   <li><b>인프라 없음</b> — CI 에서 돈다. 추론 서버가 필요하면 평가가 CI 밖으로 나가고,
 *       그러면 나빠져도 아무도 모른다</li>
 * </ul>
 *
 * <p><b>여기서 학습하지 않는다.</b> 학습은 평가 하네스가 하고 그 결과 가중치를 여기에 준다.
 * 서비스가 스스로 다시 학습하면 언제 무엇으로 배운 모델인지 못 짚는다.
 * 상황 3.4 의 변경 감시와 같은 이유다.
 */
public class LogisticFraudRiskModel implements FraudRiskModel {

    private final double[] weights;
    private final double bias;

    /**
     * @param weights {@link SequenceFeatures#toArray()} 와 <b>같은 순서</b>의 가중치
     * @param bias    절편
     */
    public LogisticFraudRiskModel(double[] weights, double bias) {
        if (weights.length != SequenceFeatures.NAMES.size()) {
            throw new IllegalArgumentException(
                    "가중치 %d 개인데 피처는 %d 개다. 순서가 어긋나면 조용히 틀린 점수가 나온다"
                            .formatted(weights.length, SequenceFeatures.NAMES.size()));
        }
        this.weights = weights.clone();
        this.bias = bias;
    }

    @Override
    public double risk(SequenceFeatures features) {
        double[] x = features.toArray();
        double z = bias;
        for (int i = 0; i < x.length; i++) {
            z += weights[i] * x[i];
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * 어느 피처가 이 점수를 만들었는지. <b>큰 것부터</b>.
     *
     * <p>심사자에게 "모델이 0.87 을 냈다"만 주면 그 숫자를 확인할 방법이 없다.
     * 상황 3.3 에서 본 대로 <b>모델이 말하는 신뢰도는 못 믿는다.</b> 대신 어느 피처가
     * 얼마나 밀었는지를 주면 그 값은 사실이라 사람이 대조할 수 있다.
     */
    public String[] topContributors(SequenceFeatures features, int k) {
        double[] x = features.toArray();
        record Contribution(String name, double value) {}
        java.util.List<Contribution> all = new java.util.ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            all.add(new Contribution(SequenceFeatures.NAMES.get(i), weights[i] * x[i]));
        }
        all.sort(java.util.Comparator.comparingDouble(Contribution::value).reversed());
        return all.stream().limit(k)
                .map(c -> "%s %+.2f".formatted(c.name(), c.value()))
                .toArray(String[]::new);
    }

    public double[] weights() {
        return weights.clone();
    }

    public double bias() {
        return bias;
    }
}
