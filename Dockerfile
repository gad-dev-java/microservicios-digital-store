FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY services ./services

ARG SERVICE_NAME
RUN mvn -pl services/${SERVICE_NAME} -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ARG SERVICE_NAME
COPY --from=build /workspace/services/${SERVICE_NAME}/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
