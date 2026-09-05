# syntax=docker/dockerfile:1.7
# The syntax directive is what makes the `--mount=type=cache` lines below legal: they are a
# BuildKit feature, and without it the build fails on the first RUN.

# Build stage
FROM maven:3.9.6-eclipse-temurin-21-jammy AS builder
WORKDIR /app
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY ./src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
# compose.yaml maps the host port onto 80, so the service has to actually listen there.
ENV SERVER_PORT=80
EXPOSE 80
ENTRYPOINT ["java", "-jar", "app.jar"]
