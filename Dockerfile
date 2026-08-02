FROM maven:3.9-amazoncorretto-21 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package

FROM amazoncorretto:21

WORKDIR /app

RUN mkdir -p /data/images \
    && chown -R 10001:0 /app /data/images

COPY --from=build --chown=10001:0 \
    /workspace/target/recognition-validator-app-*.jar /app/app.jar

USER 10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
