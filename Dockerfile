# CI-specific Dockerfile for packaging pre-built JAR
# This Dockerfile is used in GitHub Actions workflow where the JAR is built
# with Maven before Docker build, avoiding the need for GCP credentials in Docker.

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Copy the pre-built JAR from Maven build
COPY target/*.jar app.jar

# Set ownership to non-root user
RUN chown -R appuser:appgroup /app

# Expose the application ports (HTTP and gRPC)
EXPOSE 8081 9090

# Switch to non-root user
USER appuser

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]