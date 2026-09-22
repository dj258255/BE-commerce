# syntax=docker/dockerfile:1

# ── 빌드 ─────────────────────────────────────────────────────────────────────
# multi-stage인 이유: JDK·Gradle 캐시를 최종 이미지에 남기지 않는다.
# 런타임 이미지에는 jar와 JRE만 들어간다.
#
# 무엇이 컨텍스트에 들어오는지는 .dockerignore가 정한다 — personalization/data(29GB)를
# 빼지 않으면 빌드가 사실상 불가능하다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY . .

# 테스트는 CI가 돈다(./gradlew clean test). 여기서는 기동할 jar만 만든다.
# Gradle 캐시는 빌드 간에 마운트로 재사용한다 — 안 그러면 매 빌드가 의존성을 다시 받는다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

# ── 실행 ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

# healthcheck가 쓴다. JRE 이미지에는 curl이 없다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 appuser

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

# root로 돌리지 않는다.
USER appuser

# 업로드 이미지는 이미지에 굽지 않는다 — 29GB다. 볼륨으로 붙인다(compose.yaml 참고).
VOLUME ["/app/uploads"]

EXPOSE 8080

# 컨테이너 메모리를 인식하고 힙 상한을 limit의 75%로 잡는다.
# ExitOnOutOfMemoryError: 힙이 터지면 반쪽짜리로 버티지 말고 즉시 죽어 재시작되게 한다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
