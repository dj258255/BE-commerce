# ADR-080. media를 별도 Gradle 모듈로 떼면서 순환을 막는 법

- 상태: **채택**
- 날짜: 2026-10-08
- 관련: R32, [ADR-049](ADR-049-repo-layout-by-area.md)(저장소 영역 분리), [ADR-029](ADR-029-deployment-unit-vs-service-boundary.md)(배포 단위 분리), [ADR-018](ADR-018-module-internal-packages.md)(모듈 공개 API), `commerce/settings.gradle`, `media/build.gradle`

## 맥락

명세(42문서) 5절은 "코드 위치: 저장소 최상위 새 영역 `media/`. 주문·결제·재고 확정은
`commerce/`를 호출"이라고 정했었는데, R21·R22·R25·R26은 전부 `commerce/.../shorts` 안에
구현됐다 — 이 ADR이 그 이탈을 바로잡는다. 요구사항은 분명했다: media는 **별도 Gradle
하위 프로젝트**이고 commerce와 **같은 jar로 배포**한다(ADR-029가 이미 "배포 단위만 나누고
서비스는 안 나눈다"를 이 저장소의 기준선으로 세웠다 — media도 그 기준선을 따른다. 서비스로
나눴다면 이 ADR 자체가 필요 없었을 것이다).

문제는 방향이다. commerce 애플리케이션(메인 jar, `BeCommerceApplication`)이 media를
포함해야 하므로 `commerce → media` 의존이 생긴다. 그런데 `shorts`(media로 옮길 모듈)는
R25에서 상품 이름·가격을 읽으려고 `order.ProductCatalogFacts`(commerce 소유)를 직접 불렀다.
그대로 옮기면 `media → commerce`도 생겨 **Gradle이 설정 단계에서 바로 거부하는 순환
의존**이 된다.

## 결정: 의존을 역전하고, 공유 유틸 두 개는 물리적으로 옮긴다

**media가 필요한 것을 스스로 인터페이스로 선언하고(포트), 구현(어댑터)은 commerce 쪽에
둔다.** 이것만으로는 안 끝났다 — `shorts`의 기존 코드는 `shared.DomainException`(예외
계층)과 `shared.Ulid`(객체 키 생성)도 썼는데, 둘 다 commerce의 `shared` 패키지에 있었다.
같은 순환 문제가 **상품 조회가 아닌 두 번째 자리**에서도 났다.

| 무엇 | 문제 | 해결 |
|---|---|---|
| 상품 조회(R25) | `shorts`(media)가 `order.ProductCatalogFacts`(commerce)를 직접 부름 | **포트/어댑터**: media가 `ProductLookup` 인터페이스를 스스로 선언. 구현(`ShortsProductLookupAdapter`)은 commerce 쪽에 두고 `ProductCatalogFacts`를 감싼다 |
| 예외 계층 | `ShortsException extends DomainException`(commerce 소유) | **물리적 이전**: `DomainException`·`Ulid`를 media로 옮긴다. **패키지 이름(`com.beomsu.becommerce.shared`)은 그대로 둔다** — commerce의 18개 파일이 쓰는 `import ... DomainException`이 한 글자도 안 바뀐다 |

두 해법 다 결과는 같다: **media는 어떤 commerce 클래스도 import하지 않는다.** commerce는
media에 의존하고(포함하려고), media가 정의한 포트의 구현과(어댑터) `DomainException`/`Ulid`를
쓰는 나머지 모든 모듈은 그 사실을 몰라도 된다(코드가 안 바뀌었다).

## 왜 이렇게 생겼나 — split package

어댑터(`ShortsProductLookupAdapter`)는 commerce Gradle 모듈의 소스 트리에 있지만 패키지는
media와 **똑같이** `com.beomsu.becommerce.shorts`다. `DomainException`·`Ulid`는 반대로
media 모듈의 소스 트리에 있지만 패키지는 commerce의 나머지 `shared`(crypto·outbox·Money)와
**똑같이** `com.beomsu.becommerce.shared`다. 같은 패키지가 두 Gradle 모듈에 걸쳐 있다
(split package). 이 저장소는 Java 모듈 시스템(JPMS, `module-info.java`)을 쓰지 않으므로
합법이고, commerce가 media에 의존하는 한 classpath에서 둘 다 보인다.

**이게 왜 더 나은 선택인가**: 대안은 `DomainException`·`Ulid`(그리고 혹시 더 필요해질
이런 공유 유틸)를 위해 **세 번째 Gradle 모듈**(예: `shared-kernel/`)을 새로 만드는 것이었다.
그러면 의존 그래프는 더 "교과서적"이지만(`shared-kernel ← media`, `shared-kernel ← commerce`,
`media ← commerce`), 지금 media가 실제로 필요로 하는 건 **자기 자신도 안 쓰는 2개의
작은 순수 파일뿐**이다. 세 번째 모듈·새 `build.gradle`·새 디렉터리를 얻는 대신, 두 파일을
물리적으로 옮기고 패키지 이름을 지키는 쪽이 **지금 필요한 만큼만** 바꾸는 선택이었다.
media가 commerce의 다른 것들(Money, crypto)도 필요해지면 그때 다시 본다.

## 모듈 경계 검증(Spring Modulith)은 그대로 통과한다

Spring Modulith의 `ApplicationModules`·`ModularityTests`는 **Gradle 프로젝트가 아니라
패키지**를 본다. `shorts`·`shared` 둘 다 패키지 이름이 그대로라, 합쳐진 classpath에서는
이전과 똑같은 모듈로 보인다. `shorts`의 `package-info.java`(media 쪽에 있다)는 여전히
`allowedDependencies = { "shared", "order" }`다 — 어댑터가 물리적으로 commerce에 있어도
패키지 기준으로는 "shorts가 order를 쓴다"이므로 이전과 같은 선언이 맞다. `ModularityTests`를
실제로 돌려 확인했다(검증 절 참고).

## 테스트는 어디 있는가

| 테스트 | 위치 | 왜 |
|---|---|---|
| `ShortVideoTest`·`ShortVideoProductLinkingTest`·`ShortsFeedPageTest`·`UploadMetaTest` | **media** | 순수 단위 테스트(Spring 컨텍스트 없음). commerce를 전혀 모른다 |
| `ShortsApiIntegrationTest` | **commerce** (옮기지 않음) | `@SpringBootTest`로 `BeCommerceApplication`(commerce 소유)을 통째로 띄우고 `SharedContainers`(commerce의 Testcontainers 헬퍼)를 쓴다. media가 이걸 쓰려면 media→commerce 테스트 의존이 생기는데, 그 자체가 또 순환 위험이다(프로덕션 코드가 아니라 테스트 구성이라도 Gradle이 두 프로젝트의 설정을 서로 참조하게 만든다). "조립된 애플리케이션 전체"를 검증하는 테스트는 그 애플리케이션을 소유한 쪽(commerce)에 두는 것이 맞다 — media는 자기 도메인 로직만 책임진다 |

## Dockerfile · CI

**운영 `Dockerfile`은 바꾸지 않았다.** 빌드 단계가 이미 `COPY . .`(저장소 전체, `media/`
포함) 후 `./gradlew -p commerce bootJar`를 돈다 — `commerce/settings.gradle`에 media를
하위 프로젝트로 선언해 두면 Gradle이 자동으로 media를 먼저 빌드해 `BOOT-INF/lib/media-*.jar`로
최종 jar에 넣는다(직접 확인: jar 안에 `media-0.0.1-SNAPSHOT.jar`가 들어 있고, `rootProject.name`을
안 바꿔서 최종 산출물 이름도 ADR-049의 제약대로 `be-commerce-0.0.1-SNAPSHOT.jar` 그대로다).
`.dockerignore`도 `media/`를 빼지 않는다(빌드 산출물 디렉터리만 뺀다).

**CI(`ci.yml`)는 테스트 결과 경로만 더했다.** `./gradlew -p commerce clean test`는 Gradle이
동명 태스크를 하위 프로젝트까지 묶어서 돌리는 기본 동작 덕에 `:media:test`도 자동으로
함께 돈다(확인함). 다만 결과 XML이 `media/build/test-results/test/`에 따로 남아, "건너뛴
테스트 검사"와 "리포트 업로드" 두 스텝을 media 경로용으로 하나씩 더했다.

## 검증

- `./gradlew -p commerce projects` — `:media`가 하위 프로젝트로 보임
- `./gradlew -p commerce test` — **BUILD SUCCESSFUL**. commerce 1,373건 + media 35건 =
  1,408건, 전부 실패 0 (이전 합계와 같다 — 옮기면서 잃은 테스트가 없다)
- `ModularityTests`(모듈 경계 검증) 통과
- `./gradlew --no-daemon -p commerce bootJar -x test` — 성공, `be-commerce-0.0.1-SNAPSHOT.jar`
  안에 `media-0.0.1-SNAPSHOT.jar`가 `BOOT-INF/lib/`로 들어 있음을 직접 열어 확인
- 재기동 후 `GET /api/v1/shorts/feed`(공개) 200, `GET /api/v1/shorts/1`(판매자 전용) 401 —
  이전과 동일(동작을 바꾸지 않는 이전이 맞다는 확인)

## 버린 것

- **세 번째 Gradle 모듈(`shared-kernel`)을 만들지 않았다.** 지금은 옮길 것이 2개 파일뿐이라
  과한 구조였다. media가 commerce의 다른 공유 타입을 더 필요로 하게 되면 이 결정을 다시 본다
- **"포트는 media가 선언하고 어댑터는 commerce가 구현한다"는 원칙을 Money나 crypto
  같은 다른 공유 타입에는 아직 안 넓혔다** — 지금 media가 그걸 안 쓰기 때문이다
- **split package가 README만 읽는 사람에게는 안 보인다.** `com.beomsu.becommerce.shorts`
  패키지의 파일 하나(`ShortsProductLookupAdapter`)가 다른 Gradle 모듈에 있다는 사실은
  이 ADR과 그 파일의 자바독에만 적혀 있다 — grep으로 패키지 전체를 찾으면 두 디렉터리에서
  나온다는 것을 알아야 한다
- **media의 `integrationTest`(Spring Modulith 변경 기반 선택)는 다시 보지 않았다.**
  `tools/ci_modulith_scope.py`가 media 쪽 변경을 어떻게 범위로 잡는지는 확인하지 않았다 —
  이 저장소의 `integrationTest`는 이번 변경이 건드리지 않는 commerce 전용 태스크라서다
