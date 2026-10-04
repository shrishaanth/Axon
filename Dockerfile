# Build:  docker build -t axon .
# Run:    docker run -p 8080:8080 -e AXON_DB_URL=jdbc:postgresql://host:5432/axon -e AXON_DB_USER=... -e AXON_DB_PASSWORD=... axon
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY axon-core/pom.xml axon-core/pom.xml
COPY axon-cli/pom.xml axon-cli/pom.xml
COPY axon-eval/pom.xml axon-eval/pom.xml
COPY axon-server/pom.xml axon-server/pom.xml
RUN mvn -q -B -pl axon-core,axon-server -am dependency:go-offline
COPY axon-core axon-core
COPY axon-server axon-server
RUN mvn -q -B -pl axon-core,axon-server -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/axon-server/target/axon-server-*-exec.jar app.jar
# Sized for a 512 MB instance: 256 MB heap is the largest spec's measured need (docs/m1-parse-survey.md).
ENV JAVA_OPTS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=128m -Duser.timezone=UTC"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
