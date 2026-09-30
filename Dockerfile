FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests


FROM ubuntu:22.04

WORKDIR /app

RUN apt-get update \
    && apt-get install -y openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/campusone-1.0.0.jar /app/app.jar

EXPOSE 10000

CMD ["java", "-jar", "/app/app.jar"]
