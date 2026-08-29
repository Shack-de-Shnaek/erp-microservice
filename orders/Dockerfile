# Build stage
FROM maven:3.9.6-eclipse-temurin-21-jammy AS builder
WORKDIR /app
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY ./src ./
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl lecture-planning-service -am clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y postgresql-client bash
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
