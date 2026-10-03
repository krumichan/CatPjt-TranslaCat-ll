# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-noble AS tested
WORKDIR /source
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/
RUN chmod +x gradlew && ./gradlew --no-daemon test installDist
FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=tested /source/build/install/CatPjt-TranslaCat-ll/lib/ /app/lib/
COPY src/main/resources/application.yaml /app/config/application.yaml
COPY config/application-prod.yaml /app/config/application-prod.yaml
COPY --chmod=0555 deploy/oci/entrypoint.sh /app/entrypoint.sh
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["/app/entrypoint.sh"]
