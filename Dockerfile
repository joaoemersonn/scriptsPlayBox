# Stage 1: Build Java application with Maven
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Pre-cache Maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and compile package
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="PlayBox"
LABEL description="PlayBox Scheduler & Monitoring Docker Application"

WORKDIR /app

# Configure timezone to America/Sao_Paulo
ENV TZ=America/Sao_Paulo
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Prepare persistent data directory
RUN mkdir -p /app/data /app/logs

# Copy application JAR from builder stage
COPY --from=builder /build/target/playbox-scheduler-*.jar /app/app.jar

# Install curl for docker healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

EXPOSE 8080

VOLUME ["/app/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-Duser.timezone=America/Sao_Paulo", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]

