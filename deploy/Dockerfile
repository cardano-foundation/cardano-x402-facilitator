# Facilitator application image — multi-stage: Gradle build -> slim JRE runtime.
# Runtime is a glibc-based Temurin JRE (NOT alpine/musl): aiken-java-binding
# loads a bundled native .so for the `script` method's UPLC apply-params.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Warm the dependency cache first for faster incremental rebuilds.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Non-root runtime user.
RUN useradd --system --uid 10001 --home /app facilitator
COPY --from=build /src/build/libs/*.jar /app/facilitator.jar
USER facilitator
EXPOSE 4022
# Container-aware heap; virtual threads are enabled in application.yml.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/facilitator.jar"]
