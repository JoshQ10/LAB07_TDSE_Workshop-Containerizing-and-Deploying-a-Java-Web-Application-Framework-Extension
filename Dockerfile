FROM amazoncorretto:21

WORKDIR /app

COPY target/*.jar app.jar

ENV PORT=8080
ENV APP_ENV=production

EXPOSE 8080

# Exec form: the JVM is PID 1, so `docker stop` (SIGTERM) reaches it
# directly and triggers the framework's graceful-shutdown hook.
ENTRYPOINT ["java", "-jar", "app.jar"]
