# Multi-stage build — pass MODULE=<service-dir> at build time
ARG MODULE=market-data-service
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -pl ${MODULE} -am clean package -DskipTests -q 2>/dev/null || \
    (apt-get update -qq && apt-get install -y -qq maven && mvn -pl ${MODULE} -am clean package -DskipTests -q)

ARG MODULE
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG MODULE
COPY --from=build /workspace/${MODULE}/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
