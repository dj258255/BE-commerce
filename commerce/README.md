# commerce — 주문·결제·정산 본체

이 디렉터리가 **Gradle 프로젝트 루트**다. wrapper 는 저장소 루트에 하나 있으므로 실행은 `-p` 로 한다.

```bash
./gradlew -p commerce test                 # 기본 스위트(chaos·integration·eval 제외)
./gradlew -p commerce integrationTest      # Testcontainers 실 MySQL
./gradlew -p commerce bootRun              # 기동 (docker compose up -d 먼저)
./gradlew -p commerce/consumer-app build   # DLT 재처리 소비자(별도 빌드)
```

## 무엇이 여기 있고, 무엇이 밖에 있나

| 여기 | 밖 (저장소 루트) |
|---|---|
| `src/` — 모듈형 모놀리스 본체 | `docs/` — 설계·ADR·API·ERD |
| `consumer-app/` — 결제 DLT 재처리 | `monitoring/` `k6/` `tools/` — 실험·운영 자산 |
| `build.gradle` `settings.gradle` | `compose.yaml` `gradlew` `gradle/` |

**왜 `docs/` 와 `monitoring/` 이 밖에 있나.** 둘 다 commerce 와 personalization 이 **같이 쓴다.**
`monitoring/alert-rules.yml` 한 파일에 분쟁 마감 알림과 개인화 반영 지연 알림이 같이 들어 있고,
ADR 은 041 처럼 개인화 쪽 결정도 담는다. 한쪽 안으로 넣으면 다른 쪽이 남의 디렉터리를 읽게 된다.

**그래서 테스트가 루트 파일을 읽을 때는 `RepoRoot` 를 쓴다.** 작업 디렉터리는 이 디렉터리이므로
`Path.of("docs/...")` 는 여기서 안 풀린다. `"../docs/..."` 로 깊이를 박는 대신 표식을 찾아 올라간다 —
디렉터리를 한 번 더 옮겨도 안 깨지게 하려는 것이다.

## 모듈 경계

모듈 루트에는 공개 타입만 두고 구현은 `internal` 아래에 둔다. 근거는
[ADR-018](../docs/adr/ADR-018-module-internal-packages.md), 위반은 Spring Modulith 의
`ModularityTests` 가 빌드에서 잡는다.
