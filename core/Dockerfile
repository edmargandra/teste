FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build/core
COPY core/pom.xml pom.xml
COPY core/src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/core/target/core.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
