FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/fiap-soat-mecanica-execucao-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
