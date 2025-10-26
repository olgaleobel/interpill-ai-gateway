# syntax=docker/dockerfile:1

# ---- build stage: собираем fat-jar через Gradle ----
FROM gradle:8.7-jdk21-alpine AS build
WORKDIR /home/gradle/project
COPY . .
RUN gradle --no-daemon clean shadowJar

# ---- run stage: минимальный JRE 21 ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/interpill-ai-gateway-all.jar /app/app.jar

# Render прокидывает порт в $PORT
ENV PORT=8080
EXPOSE 8080
CMD ["java","-jar","/app/app.jar"]
