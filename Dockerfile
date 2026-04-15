# Claudony — JVM mode Docker image
# Build the jar first: mvn package -DskipTests
# Then build image: docker build -t claudony:latest .

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the Quarkus fast-jar layout
COPY target/quarkus-app/lib/ lib/
COPY target/quarkus-app/*.jar .
COPY target/quarkus-app/app/ app/
COPY target/quarkus-app/quarkus/ quarkus/

# tmux is required by Claudony server mode for session management
RUN apk add --no-cache tmux

EXPOSE 7777

ENTRYPOINT ["java", \
  "-Dclaudony.mode=server", \
  "-Dclaudony.bind=0.0.0.0", \
  "-jar", "quarkus-run.jar"]
