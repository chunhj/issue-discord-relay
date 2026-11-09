# Build context is the repo root: docker compose build uses `dockerfile: backend/Dockerfile`.
FROM node:24-alpine AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle gradle
RUN ./gradlew --no-daemon --version
COPY backend/src src
# The built screen goes into the jar, so Spring Boot serves it (the built files are never committed).
COPY --from=frontend /app/dist src/main/resources/static
# Tests run in CI (they need a Docker socket the image build does not have)
RUN ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx768m bootJar -x test

# The slimmer jre image lacks the jdk.random module that RetryPolicy needs through
# RandomGenerator.getDefault(), so the runtime stage stays on the full JDK image.
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=build /src/build/libs/*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
