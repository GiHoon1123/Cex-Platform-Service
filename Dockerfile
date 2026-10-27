FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app
COPY gradlew settings.gradle build.gradle gradle.properties* ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew
RUN ./gradlew --no-daemon bootJar operatorAdminJar bitcoinSignerJar bitcoinBroadcasterJar bitcoinConfirmerJar ethereumSignerJar ethereumConfirmerJar

FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin cex
COPY --from=builder --chown=cex:cex /app/build/libs/*.jar /app/
USER cex
EXPOSE 8080

# 고객·운영 API를 기본 실행하고 격리 워커는 Compose가 각 전용 JAR로 바꿔 실행한다.
CMD ["java", "-jar", "/app/cex-0.0.1-SNAPSHOT.jar"]
