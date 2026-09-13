# FDS 학습기 검증

Java로 구현한 학습기를 scikit-learn 기준자와 비교합니다.

서빙하는 모델은 자바가 학습한다(`FraudModelEvalTest.train`, 경사하강 직접 구현).
여기 있는 것은 **그 학습기가 제대로 도는지 재는 기준자**다. sklearn이 서빙 모델을
만들지 않는다.

## 왜 피처를 파이썬에서 안 만드나

파이썬이 `SequenceFeatures`를 다시 구현하면 **학습이 보는 값과 서빙이 보는 값이 갈린다.**
그 어긋남은 점수가 이상해질 때까지 안 보인다.

이 저장소는 이미 그 실패를 두 번 겪었다.

- 기기·IP 를 넣어 학습했는데 **실행 경로에서는 그 값이 안 실려 왔다.** 쓸 수 있는 값만으로
  다시 학습해 조건을 맞췄다(재현율 3.2%p 하락)
- `application.yml`의 가중치가 하네스가 찍은 값과 달랐다. `amountToMedian`은 부호까지
  뒤집혀 있었다. **평가하는 모델과 서빙하는 모델이 다른 상태로 돌고 있었다**

그래서 피처 계산은 자바 한 곳에만 둔다. 파이썬은 **자바가 계산해 내보낸 행렬만 읽는다.**
승인 경로가 JVM 안에 있으니 피처 코드도 JVM에 있어야 하고, 학습만 밖으로 나간다.

```
FraudCorpus ──► SequenceFeatures ──► build/fds/train.csv ──► train.py ──► sklearn-model.json
                    (자바 한 벌)                              (sklearn)          │
                        │                                                        ▼
                        └────────────► LogisticFraudRiskModel ◄──── agreesWithSklearn
                                            (서빙)                     (대조 테스트)
```

## 돌리는 법

```bash
python3 -m venv tools/fds/.venv
tools/fds/.venv/bin/pip install -r tools/fds/requirements.txt

./gradlew test --tests '*FraudModelEvalTest.exportsFeatureMatrix'   # 피처 행렬 내보내기
tools/fds/.venv/bin/python tools/fds/train.py                       # sklearn 학습
./gradlew test --tests '*FraudModelEvalTest.agreesWithSklearn'      # 대조
```

`sklearn-model.json`이 없으면 대조 테스트는 **건너뛴다**(`Assumptions.assumeTrue`).
파이썬이 없는 곳에서도 나머지 평가는 그대로 돌아야 한다.

## 지금 결과 (2026-09-10)

| 피처 | 손수 짠 경사하강 | sklearn | 차이 |
|---|---:|---:|---:|
| windowCount | 2.259489 | 2.250667 | 0.008822 |
| amountToMedian | 0.383182 | 0.367100 | 0.016082 |
| nearThresholdRatio | 2.921201 | 2.923188 | 0.001987 |
| **microCount** | 7.864491 | **12.961729** | **5.097238** |
| escalation | 2.225669 | 2.228753 | 0.003084 |
| deviceChurn | 2.693387 | 2.695852 | 0.002465 |
| ipChurn | -1.296761 | -1.296173 | 0.000587 |
| nightRatio | 3.102604 | 3.103968 | 0.001363 |
| bias | -4.714702 | -4.711799 | 0.002903 |

**학습 손실 0.421469 대 0.421349, 차이 0.00012.** 홀드아웃 520건에서 판정이 갈린 건 0,
상위 20 집합도 같다.

### microCount 만 벌어지는 이유

이 피처가 표본을 거의 갈라 놓아서 **정규화 없는 최적점이 무한대 방향에 있다.** 어디서
멈추느냐가 크기를 정한다. 자바 학습기를 4,000 에서 40,000 에폭으로 늘리면 7.86 → 10.62 로
자라고, sklearn은 lbfgs 로 31회 만에 12.96 까지 간다.

**크기가 달라도 판정은 같다.** 이 모델은 심사 순서를 정하는 데만 쓰므로 순서만 같으면 된다.
`trainConverges`가 40,000 에폭에서, `agreesWithSklearn`이 sklearn 대비로 각각 그것을 고정한다.

## 안 하는 것

- **sklearn 가중치를 서빙에 넣지 않는다.** 넣으려면 Python이 CI에 들어와야 하고, 그러면
  이 평가가 도는 조건이 하나 더 생긴다. 자바 학습기가 sklearn 최적점 근처에 있다는 것을
  확인했으므로 바꿀 이유가 없다
- **정규화를 켜지 않는다.** 켜면 가중치가 바뀌어 기존 재현율 55.7%와 직접 비교할 수 없다.
  판정이 바뀌지 않는 것을 확인했으므로 이 용도에서는 적용하지 않는다
