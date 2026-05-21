# Stage 1: Build
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
