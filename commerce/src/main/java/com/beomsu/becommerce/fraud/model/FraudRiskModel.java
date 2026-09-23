package com.beomsu.becommerce.fraud.model;

/**
 * 창에서 뽑은 피처로 위험도를 낸다. <b>0~1.</b>
 *
 * <p>포트를 두는 이유는 {@code assist.draft.DraftPort} 와 같다. 모델을 고르기 전에
 * "무엇이 좋은 모델인가"를 먼저 재야 하고, 포트가 있으면 평가 하네스가 어떤 구현이 오든
 * 같은 잣대로 잰다.
 *
 * <p><b>여기서 등급을 내지 않는다.</b> {@code ALLOW / REVIEW / BLOCK} 으로 끊는 것은
 * 임계를 정하는 일이고, 그 임계는 <b>막을 준비가 됐는지</b>에 달렸다(docs/17 §5). 모델은
 * 점수까지만 낸다.
 */
public interface FraudRiskModel {

    /** 위험도. 0 에 가까울수록 평소와 같다. */
    double risk(SequenceFeatures features);
}
