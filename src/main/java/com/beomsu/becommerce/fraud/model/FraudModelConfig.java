package com.beomsu.becommerce.fraud.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 학습으로 나온 가중치를 설정에서 읽어 모델을 만든다.
 *
 * <p><b>서비스가 스스로 학습하지 않는다.</b> 학습은 {@code FraudModelEvalTest} 가 하고 결과를
 * 여기에 준다. 서비스가 스스로 배우면 <b>언제 무엇으로 배운 모델인지 못 짚는다.</b> 상황 3.4 의
 * 변경 감시가 "평가는 시점 측정이라 모델이 바뀌면 그 수치가 더 이상 사실이 아니다" 라고 적은
 * 것과 같은 이유다.
 *
 * <p><b>기본값은 학습 결과 그대로다.</b> 하네스가 찍어 주는 값을 옮겨 적었고, 순서는
 * {@link SequenceFeatures#NAMES} 와 같다. 순서가 어긋나면 조용히 틀린 점수가 나오므로
 * {@link LogisticFraudRiskModel} 생성자가 개수를 먼저 본다.
 */
@Configuration
public class FraudModelConfig {

    /**
     * {@code windowCount, amountToMedian, nearThresholdRatio, microCount,
     * escalation, deviceChurn, ipChurn, nightRatio} 순서다.
     */
    @Value("${fds.model.weights:2.259489,0.383182,2.921201,7.864491,2.225669,2.693387,-1.296761,3.102604}")
    private List<Double> weights;

    @Value("${fds.model.bias:-4.714702}")
    private double bias;

    @Bean
    FraudRiskModel fraudRiskModel() {
        double[] w = new double[weights.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = weights.get(i);
        }
        return new LogisticFraudRiskModel(w, bias);
    }
}
