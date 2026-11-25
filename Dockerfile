FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY frontend ./frontend
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="--add-opens=java.base/java.nio=ALL-UNNAMED"
COPY --from=build /workspace/target/pdm-mvp-0.1.0-SNAPSHOT.jar /app/pdm-mvp.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/pdm-mvp.jar"]
