# syntax=docker/dockerfile:1
# hanstep-backend 운영 이미지. 빌드: docker build -t hanstep-backend:local .
# 1단계(JDK 21)에서 bootJar 를 만들고, 2단계(JRE 21)에는 jar 만 옮긴다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Gradle 래퍼·빌드 설정을 먼저 복사해 의존성 레이어를 캐시한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

# root 가 아닌 사용자로 실행
RUN groupadd --system --gid 10001 hanstep \
    && useradd --system --uid 10001 --gid hanstep --no-create-home hanstep
COPY --from=build --chown=hanstep:hanstep /workspace/app.jar /app/app.jar
USER hanstep

EXPOSE 8080
# JVM 옵션은 실행 시 JAVA_OPTS 로 넘긴다(예: -Xmx768m)
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
