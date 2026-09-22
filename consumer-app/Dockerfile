# syntax=docker/dockerfile:1

# consumer-app은 **독립 Gradle 프로젝트**다 — 루트 settings.gradle에 include하지 않는다(메인 빌드에 영향 없게).
# 실행은 루트 wrapper로 `./gradlew -p consumer-app …` 하므로, 빌드 컨텍스트는 리포지토리 루트다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY . .

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -p consumer-app bootJar -x test

# ── 실행 ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

RUN useradd --system --uid 1001 appuser
WORKDIR /app
COPY --from=build /workspace/consumer-app/build/libs/*.jar /app/app.jar
USER appuser

# 웹서버가 없는 워커다(spring.main.web-application-type=none) — HTTP 헬스체크가 없다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
