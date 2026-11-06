# All five services from one Maven build (built once, shared by every service image).
# Used by docker-compose.prod.yml with `target: <service>`.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml common/
COPY discovery-server/pom.xml discovery-server/
COPY api-gateway/pom.xml api-gateway/
COPY market-data-service/pom.xml market-data-service/
COPY indicator-service/pom.xml indicator-service/
COPY strategy-service/pom.xml strategy-service/
COPY tools/h2-to-postgres/pom.xml tools/h2-to-postgres/
# Download dependencies first so code changes don't re-download them.
RUN mvn -B -q -pl common,discovery-server,api-gateway,market-data-service,indicator-service,strategy-service -am dependency:go-offline || true
COPY . .
RUN mvn -B -DskipTests -pl common,discovery-server,api-gateway,market-data-service,indicator-service,strategy-service -am package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 10001 app
USER app
ENV TZ=Asia/Kolkata
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

FROM runtime AS discovery-server
COPY --from=build /workspace/discovery-server/target/*.jar /app/app.jar

FROM runtime AS api-gateway
COPY --from=build /workspace/api-gateway/target/*.jar /app/app.jar

FROM runtime AS market-data-service
COPY --from=build /workspace/market-data-service/target/*.jar /app/app.jar

FROM runtime AS indicator-service
COPY --from=build /workspace/indicator-service/target/*.jar /app/app.jar

FROM runtime AS strategy-service
COPY --from=build /workspace/strategy-service/target/*.jar /app/app.jar
