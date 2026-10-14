# syntax=docker/dockerfile:1

# Build with Maven on JDK 21. Dependencies are their own layer, so a source change does not refetch them.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/payguard-*.jar /payguard.jar

# Run on the JRE only, as an unprivileged user.
FROM eclipse-temurin:21-jre
RUN groupadd --system payguard && useradd --system --gid payguard --uid 10001 payguard
WORKDIR /app
COPY --from=build /payguard.jar /app/payguard.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/payguard.jar"]
