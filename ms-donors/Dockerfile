# ms-donors image. It depends on yawar-platform, so the build context is the repository root:
#   docker build -f ms-donors/Dockerfile -t yawar/ms-donors .
FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY yawar-platform/pom.xml yawar-platform/pom.xml
COPY ms-donors/pom.xml ms-donors/pom.xml
RUN ./mvnw -B -ntp -q -pl ms-donors -am dependency:go-offline
COPY yawar-platform/src yawar-platform/src
COPY ms-donors/src ms-donors/src
RUN ./mvnw -B -ntp -q -pl ms-donors -am package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar ms-donors/target/ms-donors-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21.0.13_9-jre-alpine
# Patched OS packages (Trivy V-03), then a numeric UID so Kubernetes runAsNonRoot can verify it.
RUN apk upgrade --no-cache && addgroup -S -g 10001 yawar && adduser -S -u 10001 -G yawar yawar
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
EXPOSE 8081
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8081/health/ready >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
