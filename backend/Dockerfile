FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=3 package -DskipTests
# The bundled Linux ONNX Runtime used by BGE requires glibc.
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system smartserve && useradd --system --gid smartserve --create-home smartserve
WORKDIR /app
COPY --from=build /build/target/smartserve-backend-2.0.0.jar app.jar
USER smartserve
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
