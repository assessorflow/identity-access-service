FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Artifact Registry SA key for pulling shared proto dependency
COPY artifact-registry-sa.json /tmp/ar-sa.json
ENV GOOGLE_APPLICATION_CREDENTIALS=/tmp/ar-sa.json

# Cache dependencies first (only re-downloads when pom.xml changes)
COPY identity-access-service/pom.xml .
RUN mvn dependency:go-offline -B

# Build the application
COPY identity-access-service/src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081 9090
ENTRYPOINT ["java", "-jar", "app.jar"]

