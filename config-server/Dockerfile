# config-server image. Build context: this folder (docker build -t yawar/config-server config-server).
FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -ntp -q dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -q package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar target/config-server-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21.0.13_9-jre-alpine
RUN addgroup -S yawar && adduser -S yawar -G yawar
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER yawar
EXPOSE 8888
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8888/health/ready >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
