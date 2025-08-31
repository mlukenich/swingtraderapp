# Stage 1: The "Builder" - Compiles the application using Maven and a full JDK
FROM maven:3.9-eclipse-temurin-17 AS build

# Set the working directory inside the container
WORKDIR /app

# Copy the pom.xml first to leverage Docker's layer caching
COPY pom.xml .
# Download dependencies
RUN mvn dependency:go-offline

# Copy the rest of the source code
COPY src ./src

# Package the application into an executable JAR, skipping tests
RUN mvn clean package -DskipTests

# ---

# Stage 2: The "Runner" - Creates the final, lightweight runtime image
FROM eclipse-temurin:17-jre-jammy

# Set the working directory
WORKDIR /app

# IMPORTANT: Declare a volume for the H2 database
# This ensures that your database file persists even when the container is removed.
VOLUME /app/data

# Copy the executable JAR from the 'build' stage
COPY --from=build /app/target/*.jar app.jar

# Expose the port the application runs on (we set this to 8081)
EXPOSE 8081

# The command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]