# ADR-009. 비밀번호 해시를 Argon2id로 옮기고, 기존 해시는 그대로 검증한다

- 상태: 채택 (Accepted)
- 날짜: 2026-08-28
- 관련: `SecurityConfig`, `RateLimitFilter`, [ADR-006](ADR-006-envelope-encryption-dek-kek.md)

## 맥락

비밀번호는 `BCryptPasswordEncoder`로 해싱해 `members.password_hash`에 저장한다. 인증은 로그인 1회 BCrypt 검증 후 JWT를 발급하는 구조다(요청당 재해싱 없음).

**BCrypt가 깨진 건 아니다. 다만 1순위 권고가 아니다.**

OWASP Password Storage Cheat Sheet는 **Argon2id를 먼저** 권하고, scrypt, 그 다음 bcrypt 순으로 둔다. bcrypt는 "레거시 시스템에서 work factor 10 이상"으로 표현된다.

BCrypt의 한계는 둘이다.

1. **72바이트 절단** — 그보다 긴 비밀번호는 조용히 잘린다. 긴 패스프레이즈를 쓰는 사용자에게 실질 엔트로피 상한이 생긴다.
2. **메모리 하드가 약하다** — 4KB 수준이라 GPU·ASIC 병렬 공격에 Argon2id/scrypt보다 취약하다.

## 결정

**Argon2id로 인코딩하고, 이미 저장된 BCrypt 해시는 계속 검증한다.**

```java
String encodingId = "argon2";
PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);  // 19MiB, t=2, p=1
PasswordEncoder bcrypt = new BCryptPasswordEncoder();

DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder(
        encodingId, Map.of(encodingId, argon2, "bcrypt", bcrypt));
delegating.setDefaultPasswordEncoderForMatches(bcrypt);
```

**세 세대의 해시를 모두 받는다.**

| 저장된 형태 | 누가 검증하나 |
|---|---|
| `{argon2}$argon2id$...` | 맵의 argon2 (신규 가입) |
| `{bcrypt}$2a$...` | 맵의 bcrypt (이관 중간 세대) |
| `$2a$...` (접두사 없음) | `setDefaultPasswordEncoderForMatches` (가장 오래된 것) |

**기존 회원이 비밀번호를 재설정하지 않아도 된다.** 이 조건을 테스트 5개로 고정했다(`PasswordEncoderMigrationTest`).

### 파라미터

OWASP 최소 권고인 **메모리 19MiB · iterations 2 · parallelism 1**을 쓴다. Spring이 제공하는 `defaultsForSpringSecurity_v5_8()`은 메모리가 **16MiB라 권고에 못 미쳐** 직접 구성했다.

## 근거

**1. BCrypt는 1순위가 아니다.** OWASP Password Storage Cheat Sheet는 Argon2id를 먼저 두고, scrypt, 그 다음 bcrypt 순이다. bcrypt는 "레거시 시스템에서 work factor 10 이상"으로 표현된다.

**2. 72바이트 절단은 실제로 재현된다.** BCrypt는 72바이트를 넘는 입력을 조용히 자른다. 그래서 앞 72바이트가 같으면 **뒤가 달라도 같은 비밀번호로 판정한다.** 긴 패스프레이즈를 쓰는 사용자에게 실질 엔트로피 상한이 생긴다. 이 동작 차이를 테스트로 고정했다.

**3. 메모리 하드가 다르다.** BCrypt는 4KB 수준이라 GPU·ASIC 병렬 공격에 상대적으로 취약하다. Argon2id는 파라미터로 메모리 비용을 강제해 전용 하드웨어의 이점을 줄인다.

**4. 접두사를 먼저 붙여 둔 덕에 교체가 작았다.** 이 ADR의 앞 판(접두사만 붙임)이 없었다면 저장된 해시가 어떤 알고리즘인지 알 수 없어 **전수 비밀번호 재설정** 말고는 이관할 방법이 없었다. 준비를 먼저 한 것이 여기서 값을 했다.

## 실측 — 교체의 대가

느린 것이 목적인 연산이라 "빨라졌다"가 개선은 아니다. 잰 이유는 ① 로그인 응답에 얼마가 붙는지 ② `/auth/login`의 DoS 표면이 얼마나 커지는지다.

**로컬(Apple Silicon), 워밍업 후 7회 중앙값**

| | 소요 |
|---|---|
| BCrypt (strength 10) | **87ms** |
| Argon2id (19MiB · t=2 · p=1) | **32ms** |

**예상과 반대로 Argon2id가 더 빨랐다.** BCrypt의 비용은 반복 횟수에서 나오고 Argon2id는 상당 부분을 메모리에서 가져오기 때문이다. 즉 **시간을 덜 쓰면서 공격자에게는 더 비싸다** — 메모리 하드의 목적이 그것이다.

**대신 메모리를 쓴다.** 로그인 1건마다 19MiB를 잡는다.

```
동시 로그인 100건 → 순간 약 1.9GB
```

**이 교체는 `/auth/login`의 DoS 표면을 오히려 키운다.** 이전에는 CPU만 태웠지만 이제 메모리도 잡는다. `RateLimitFilter`가 그 경로를 IP 기준으로 막고 있는 것이 그래서 **더 중요해졌다.** 유입 제한이 없었다면 이 교체는 위험한 변경이었을 것이다.

## 이관 절차 (완료)

1. ~~BouncyCastle 의존 추가~~ → `org.bouncycastle:bcprov-jdk18on`
2. ~~맵에 argon2 추가~~ → OWASP 최소 권고 파라미터로
3. ~~`encodingId` 변경~~ → `"argon2"`
4. ~~로그인 시 점진 재인코딩~~ → `MemberPasswordUpgradeService`

### 로그인 시 점진 이관 — 프레임워크 기본 흐름을 쓴다

**원문 비밀번호는 로그인 순간에만 존재한다.** 저장된 건 해시뿐이라 배치로는 못 옮긴다. 이 자리를 놓치면 기존 회원은 비밀번호를 재설정하기 전까지 영원히 옛 알고리즘에 남는다.

Spring Security가 이 흐름을 이미 갖고 있다.

```
로그인 성공
  → DaoAuthenticationProvider가 PasswordEncoder.upgradeEncoding(저장된해시) 질의
  → 참이면 UserDetailsPasswordService.updatePassword(user, 새로_인코딩된_해시) 호출
```

`DelegatingPasswordEncoder`는 **저장된 접두사가 현재 인코딩 id와 다르면** 참을 반환한다. 그래서 `{bcrypt}`와 접두사 없는 레거시가 모두 대상이 된다. Argon2 인코더는 **메모리·iterations 파라미터가 바뀔 때도** 참을 반환하므로, 나중에 파라미터를 올려도 같은 경로로 이관된다.

**직접 구현해야 하는 이유**: Spring의 `JdbcUserDetailsManager`는 이 인터페이스를 **일부러 구현하지 않는다.** 자동 갱신이 기존 운영 코드를 깨뜨릴 수 있어 옵트인으로 남겨 둔 설계다.

**설계 원칙 하나**: **이관 실패가 로그인을 막지 않는다.** 회원을 못 찾거나 데모 계정이면 조용히 넘어가고 인증은 통과시킨다. **이관은 편의이고 인증은 기능**이다. 테스트로 고정했다.

## 트레이드오프 / 포기한 것

- **의존이 하나 늘었다.** BouncyCastle. "이미 있는 것으로 되면 새로 안 들인다"는 이 프로젝트의 기준을 여기서는 적용하지 않았다. BCrypt와 Argon2id는 **같은 목적을 같은 강도로 달성하지 않기** 때문이다.
- **메모리 비용이 생겼다.** 위 실측 참고. 동시 로그인이 많으면 이게 먼저 아프다. 트래픽이 늘면 파라미터(19MiB)를 다시 재야 한다.
- **이관은 로그인한 사람만 된다.** 오래 안 들어온 계정은 옛 해시로 남는다. **이건 노력의 문제가 아니라 원리적 한계다** — 해시는 단방향이고 원문은 로그인 순간에만 존재한다. 코드를 더 써서 닫을 수 있는 종류가 아니다.

  **그래서 "끝"을 판정할 수 있게 만들었다.** `password_hash_legacy_count` 게이지가 남은 수를 보여주고, **0이 되면 레거시 인코더를 제거할 수 있다.** 그 시점이 이관의 진짜 종료다. 7일 이상 남으면 `PasswordHashMigrationStalled`(severity: info)가 뜬다.

  **지금 레거시 인코더를 제거하면 안 되는 이유**: 휴면 계정이 로그인 자체를 못 하게 된다. 이 프로젝트에는 **비밀번호 재설정 플로우가 없어서** 그건 이관이 아니라 계정 잠금이다. 재설정 경로를 먼저 만들거나, 게이지가 0이 되기를 기다리는 것이 순서다.
- **컬럼을 넓혀야 했다.** 실측 결과 `{argon2}` 해시가 **105자**인데 `password_hash`가 `varchar(100)`이었다. 알고리즘만 바꾸고 스키마를 안 따라가면 가입이 "Data too long"으로 터진다. **인코더 단위 테스트는 DB를 타지 않아 초록불이었다** — V23으로 255까지 넓혔다.
- 저장 문자열이 길어진다(`{argon2}` 접두사 + Argon2 인코딩). 컬럼 길이 여유를 확인했다.

## 연결 — BCrypt를 없앨 수 없는 자리

JWT 전환으로 **요청마다 하던 BCrypt 재검증**은 없앴다(min 110ms → 4.17ms). 그러나 **로그인 한 곳에서는 BCrypt가 남는다.** 비밀번호를 실제로 대조하는 자리라 없앨 수 없다.

그래서 그 경로가 **비대칭 DoS 표면**이 된다. 공격자는 요청 하나를 보내고 서버는 ~110ms의 CPU를 태운다. 비밀번호가 틀려도 해싱은 돌기 때문에 계정을 몰라도 공격이 성립한다.

`RateLimitFilter`가 `/auth/login`·`/members/signup`을 **IP 기준**으로 막는 이유가 이것이다. 이 두 경로는 인증 전이라 principal이 없어 사용자별 제한을 그냥 통과했다. 그리고 `X-Forwarded-For`는 클라이언트가 위조할 수 있어 신뢰하지 않고 소켓 피어만 쓴다.

**같은 110ms가 성능에서는 없앤 병목이고 보안에서는 남겨야 하는 비용이다.** 없앨 수 없으니 유입을 제한하는 쪽으로 갔다.

## 대안

- **지금 Argon2로 교체**: BouncyCastle 의존 추가가 필요하고, BCrypt가 안전 범위 안이라 급하지 않다. 이관 경로를 먼저 열고 필요할 때 넘어간다.
- **`PasswordEncoderFactories.createDelegatingPasswordEncoder()` 사용**: 표준 팩토리지만 Argon2·SCrypt 인코더를 즉시 생성해 **BouncyCastle 없이는 부팅이 깨진다.** 맵을 직접 구성해 회피했다.
- **그대로 두기**: 접두사가 없으면 나중에 전수 재설정 말고는 이관 방법이 없다. 되돌리기 비싼 선택이라 기각.

## 실측 보강 (2026-08): 메모리는 동시성과 곱해진다

결정 당시 "19MiB를 쓴다"고만 적었는데, 그건 **해시 1건당**이다. 동시 로그인 수만큼 곱해진다는
사실을 수치로 확인하지 않았다. 재보니 이랬다(성능 리포트 11절, `tools/Argon2Concurrency.java`).

- 사용량은 정확히 선형이다 — **동시 1건당 약 20MB**(동시 100건 = 1,984MB).
- 경험칙: **필요 힙(Argon2 몫) ≈ Tomcat 스레드 수 × 20MB.**
  현재 설정(`server.tomcat.threads.max=100`)에서 **Argon2 몫만 2GB**다.
- 힙 512MB에서 동시 50건은 **전부 OOM**, 동시 100건은 75건이 실패했다.

**실패 방식이 더 문제다.** 성공했을 때 0.7초인 작업이 힙이 모자라면 12.1초를 쓰고 죽는다.
느려지면서 죽으니 클라이언트가 재시도하고, 재시도가 다시 메모리를 잡는다. 스스로 증폭된다.

이 ADR이 "유입 제어가 이미 있으므로 DoS 표면 증가를 감당할 수 있다"고 적었던 근거는 유지되지만,
**전제가 하나 추가된다** — 유입 제어의 전역 상한(로그인 경로 100/s)은 고정 윈도우라 100건이
같은 순간에 도달할 수 있다. 즉 상한값 자체가 위험 구간과 겹친다. 이 둘(상한값·힙)은 함께 정해야
하고, 어느 한쪽만 보고 정하면 안 된다.

**조치**: `PasswordHashingCapacityCheck`가 기동 직후 `스레드 수 × 20MB` 대 힙 상한을 비교해
경고한다. 부팅을 막지는 않는다 — 전 스레드 동시 해시는 최악의 경우이고, 힙이 빠듯하다고 부팅을
거부하면 그게 더 큰 사고다. 판단은 배포자가 하되 모르고 지나가지 않게 한다.

**남기는 한계**: 세마포어로 동시 해시 수를 직접 묶으면 메모리 상한이 스레드 설정과 분리돼 더
정확해진다. 대기열이 하나 더 생기고 그 큐의 포화 동작을 정해야 해서 지금은 택하지 않았다.
전역 상한을 실측 기반으로 재조정할 때 같이 다룰 문제다.

## 실측 보강 (2026-09): 세마포어 상한과 이관 계측

앞의 "남기는 한계"는 그 뒤 `HashConcurrencyLimiter`로 해소했다. 동시 해시 수를 세마포어로 묶고,
자리가 없으면 대기시키지 않고 즉시 거절(`HashCapacityExceededException`)한다. 기본 상한은
`app.auth.hash-concurrency:8`이다. 실측에서 동시 1건당 약 21MB가 잡혀 512MB 힙에서는 동시 32까지
피크 508MB로 버티고 동시 64부터 실패가 나며, 2GB 힙에서는 동시 100건이 피크 2,035MB다. 같은
조건에서 상한 8을 걸면 피크가 173MB로 떨어지고 p95가 1,514ms에서 96ms로 내려간다. 대신 초과분은
거절된다.

**계측을 둘 더했다.** `auth.hash.rejected`(거절 누계)와 `password.hash.upgrade.failed`(재인코딩
실패 누계)다. 게이지 `password.hash.legacy.count`는 남은 수만 말하므로, 왜 줄지 않는지는 실패
건수를 봐야 안다. 이관 저장이 실패하면 그 자리에서 세고 그대로 올린다 — 같은 트랜잭션에서 예외를
잡아도 rollback-only라 커밋이 실패하므로, 삼키려면 별도 트랜잭션이 필요하다.
