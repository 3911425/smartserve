FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S smartserve && adduser -S smartserve -G smartserve
WORKDIR /app
COPY --from=build /build/target/smartserve-backend-1.0.0.jar app.jar
USER smartserve
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
