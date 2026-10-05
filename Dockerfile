# stage 1: build
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

# install Maven and Node.js
RUN apt-get update && apt-get install -y maven curl ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# copy npm files and install dependencies
COPY package.json package-lock.json ./
COPY frontend/package.json ./frontend/package.json
RUN npm ci

# copy project files
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY frontend ./frontend
COPY src ./src

RUN mvn -B -e -q -DskipTests package

# stage 2: runtime
FROM eclipse-temurin:25-jdk-noble
WORKDIR /app

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]