FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /app
COPY src/AuraTuneServer.java ./src/AuraTuneServer.java
RUN mkdir -p out && javac -d out src/AuraTuneServer.java

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
RUN useradd --system --uid 10001 --create-home appuser
COPY --from=build --chown=appuser:appuser /app/out ./out
COPY --chown=appuser:appuser web ./web

USER appuser
EXPOSE 8080
CMD ["java", "-cp", "out", "AuraTuneServer"]
