# Stage 1: Build the Spring Boot application
FROM mcr.microsoft.com/openjdk/jdk:25-ubuntu AS build

WORKDIR /workspace

RUN apt-get update && apt-get install -y maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src ./src

RUN mvn -B -DskipTests package


# Stage 2: Run the application
FROM mcr.microsoft.com/openjdk/jdk:25-ubuntu

WORKDIR /app

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]