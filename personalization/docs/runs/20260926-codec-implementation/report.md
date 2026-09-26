# SNAPPY 가 LZ4 보다 싼 이유를 구현별로 가르다 (#346)

[ADR-041](../../../../docs/adr/ADR-041-cache-compression-threshold.md)은 캐시 값 압축 코덱으로 SNAPPY 를 골랐다. 앱 안 측정에서 500KB CPU 가
SNAPPY 242ms 대 LZ4 359ms(500왕복, +48%)로 교과서 순서와 반대였고 "왜 뒤집혔는지는 확인하지 못했다"고 적었다.
예측과 판정 기준은 [이슈 #346](https://github.com/dj258255/BE-commerce/issues/346)에 **측정 전에** 적었고, 추가 측정 둘도 측정 전에 댓글로 적었다.

- **앱 안에서 LZ4 는 Java 구현(`LZ4Factory:JavaUnsafe`)으로 돌았다.** lz4-java 의 `fastestInstance()` 는 시스템 클래스로더가 올린 경우에만 JNI 를 시도하는데,
  스프링 부트 실행 jar 는 자체 클래스로더로 올린다. Snappy 는 앱 안에서도 네이티브였다
- 같은 코드를 단독(테스트)으로 돌리면 LZ4 는 **JNI** 로 골라진다. 이때도 Snappy 가 500KB 왕복에서 **8.7%** 싸다. 코덱 층의 추가 비용은 +0.9% 다
- 그래서 ADR 의 +48% 는 대부분 **클래스로더가 고른 구현** 차이였고, Snappy 를 고른 결정은 어느 쪽으로 돌려도 그대로다

## 방법

- `CodecImplementationExperimentTest`(`@Tag("experiment")`): 같은 페이로드(`CacheBenchmark.payload`, 반복 키 · 조금씩 다른 값의 JSON) 1KB · 50KB · 500KB 에서
  구현마다 압축 + 해제 한 왕복의 **스레드 CPU 시간**. 구현마다 3초 데우기 뒤 0.4초 라운드 15번의 중앙값, 라운드마다 구현 순서를 돌렸다. JMH 는 쓰지 않았다
- 식힌 변형: 호출마다 64MB 를 훑어 캐시를 비운 뒤 한 번씩 300번(훑는 시간은 재지 않는다)
- 앱 안: `tools/run-cache-compression.sh` 를 코덱 LZ4 · SNAPPY, 50KB · 500KB, 구간당 500개로 다시 돌렸다(ADR 과 같은 측정)
- 어느 구현이 골라졌는지: 단독 실행과 앱에서 각각 `vmmap` 으로 네이티브 라이브러리 매핑을 보고, 앱에는 기동 로그를 더해 확인했다

## 결과

**단독, 데운 캐시(두 번 재서 0.1% 안에서 같았다)**

| 크기 | Snappy | LZ4 JNI(골라진 것) | LZ4 Unsafe | LZ4 순수 Java | 코덱 층 LZ4 | 코덱 층 Snappy |
|---:|---:|---:|---:|---:|---:|---:|
| 1KB | 1.50µs | 1.88µs | 1.80µs | 2.49µs | 1.92µs | 1.51µs |
| 50KB | 27.6µs | 31.3µs | 43.5µs | 58.6µs | 31.6µs | 27.6µs |
| 500KB | 252.8µs | 274.8µs(+8.7%) | 450.2µs(+78%) | 626.2µs | 277.2µs | 253.0µs |

**단독, 식힌 캐시(중앙값)**: 50KB Snappy 41 · LZ4 JNI 44 · LZ4 Unsafe 54µs, 500KB 278 · 302(+8.6%) · 482µs. 캐시를 식혀도 차이는 그대로였다.

**앱 안(재측정)**

| 코덱 | 크기 | CPU(500왕복) | 압축 p50 | 해제 p50 | 저장/원본 |
|---|---:|---:|---:|---:|---:|
| LZ4 | 50KB | 150.63ms | 0.04ms | 0.04ms | 0.168 |
| SNAPPY | 50KB | 96.07ms | 0.04ms | 0.03ms | 0.163 |
| LZ4 | 500KB | 376.62ms(**+45%**) | 0.39ms | 0.24ms | 0.166 |
| SNAPPY | 500KB | 259.80ms | 0.26ms | 0.16ms | 0.170 |

원래 측정(242 대 359ms, +48%)을 재현했다.

**골라진 구현**

| 실행 | LZ4 | Snappy |
|---|---|---|
| 단독(테스트 · `java -cp lz4-java.jar`) | `LZ4Factory:JNI`, `liblz4-java-*.dylib` 매핑 | 네이티브 1.1.10 |
| 앱(스프링 부트 실행 jar) | `LZ4Factory:JavaUnsafe`, LZ4 네이티브 매핑 없음 | `libsnappyjava.dylib` 매핑 |

lz4-java 1.8.0 `LZ4Factory.fastestInstance()` 는 `Native.isLoaded() || Native.class.getClassLoader() == ClassLoader.getSystemClassLoader()` 일 때만
`nativeInstance()` 를 시도한다. 실행 jar 에서는 둘 다 거짓이라 바로 Java 구현으로 간다.

## 예측과 비교

- 예측: lz4-java 에 darwin-aarch64 네이티브가 없어 Unsafe 가 골라졌을 것이다. **절반만 맞았다.** 네이티브는 있었고 단독 실행에서는 JNI 가 골라졌다.
  앱 안에서 Java 가 골라진 이유는 플랫폼이 아니라 클래스로더였다
- 예측: 코덱 층 비용은 500KB 에서 10% 안팎. **틀렸다(0.9%).** 머리 4바이트와 복사는 거의 비용이 없다
- 추가 측정 예측: 캐시를 식히면 LZ4 JNI 가 Snappy 보다 30% 넘게 비싸질 수 있다. **아니었다(+8.6%).** 그래서 앱 경로의 다른 원인을 찾았고 클래스로더였다

## 판정

- 기준 2(골라진 구현이 JNI 인데도 Snappy 가 싸면 추정은 틀렸다)는 **단독 실행**에서 성립했다: JNI 끼리도 Snappy 가 8.7% 싸다
- 기준 1(골라진 구현이 JNI 가 아니고 Snappy 가 네이티브면 "네이티브 대 Java")은 **앱 안**에서 성립했다. 다만 원인은 플랫폼이 아니라 클래스로더다
- 코덱 기본값은 바꾸지 않는다(ADR-041: 적용할 캐시가 없다). 코덱 구현을 기동 로그로 밝히게만 했다

## 이 표가 말하지 않는 것

- Snappy 가 JNI LZ4 보다 8.7% 싼 이유(알고리즘인지 JNI 경계 비용인지)는 가르지 않았다. 이 페이로드(반복 키 JSON)와 이 맥에서의 값이다
- 앱 안의 LZ4 를 `nativeInstance()` 로 명시하면 JNI 로 돌 것으로 보지만 바꾸지 않았고 재지 않았다
- JMH 가 아니다. 한 JVM 에서 구현 순서를 돌리는 방식이라 JIT 상태가 구현마다 다를 수 있다

## 재현

```bash
CODEC_OUT=/tmp/codec.json CODEC_COLD_OUT=/tmp/codec-cold.json ./gradlew -p commerce experimentTest --tests '*CodecImplementationExperimentTest'
./gradlew -p commerce bootJar && CODECS="LZ4 SNAPPY" SIZES="51200 512000" bash tools/run-cache-compression.sh
# 앱 기동 로그: "캐시 코덱 LZ4 구현=LZ4Factory:JavaUnsafe"
```

원자료: [`raw/`](raw/) (단독 두 회차 · 식힌 변형 · 앱 안 재측정 · 골라진 구현의 근거 · 같은 맥의 CPU)
