FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
WORKDIR /app
COPY daily-rhythm.jar /app/daily-rhythm.jar
COPY health/com/dayoudu/cloud/HealthProbe.class /app/health/com/dayoudu/cloud/HealthProbe.class
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/daily-rhythm.jar"]
