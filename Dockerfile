# syntax=docker/dockerfile:1
# Build stage: compile and package the self-contained jar.
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle gradle.properties ./
COPY src ./src
RUN gradle jar --no-daemon -q

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 stockpulse
COPY --from=build /src/build/libs/stockpulse-*.jar /app/stockpulse.jar
USER stockpulse
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/stockpulse.jar"]
