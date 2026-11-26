# api-gateway image. Build context: this folder (docker build -t yawar/api-gateway api-gateway); no dependency on yawar-platform.
FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -ntp -q dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -q package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar target/api-gateway-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21.0.13_9-jre-alpine
# Patched OS packages (Trivy V-03), then a numeric UID so Kubernetes runAsNonRoot can verify it.
RUN apk upgrade --no-cache && addgroup -S -g 10001 yawar && adduser -S -u 10001 -G yawar yawar
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
EXPOSE 8000
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8000/health/ready >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
