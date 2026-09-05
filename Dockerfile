# Builder stage — extract Spring Boot layers (needs a shell/JDK)
FROM eclipse-temurin:25-jre AS builder
WORKDIR /app
COPY target/demo-0.0.1-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Runtime stage — distroless, only the layers copied in
FROM gcr.io/distroless/java25-debian12
WORKDIR /app
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
