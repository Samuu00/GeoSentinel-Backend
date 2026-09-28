# Build & Dependency Resolution
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Cache delle dipendenze Maven (mvnw è uno script sh: scarica Maven con il wget/unzip di busybox, niente bash)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw
RUN ./mvnw dependency:go-offline -B

# Copia i sorgenti ed esegui la build saltando i test
COPY src/ ./src/
RUN ./mvnw clean package -DskipTests -B

# Estrai i layer di Spring Boot per ottimizzare la cache Docker.
# Boot 4 ha rimosso il jarmode "layertools" (Unsupported jarmode): si usa "tools" con --layers --launcher
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted


# Lightweight Runtime Image
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Hardening di sicurezza: crea utente e gruppo di sistema non privilegiati
RUN addgroup -S geosentinel && adduser -S geosentinel -G geosentinel

# Copia i layer estratti dallo stage precedente in ordine di frequenza di modifica.
# Niente --chown: i file restano di root e l'utente dell'app può solo leggerli, non modificare il proprio codice
COPY --from=builder /workspace/extracted/dependencies/ ./
COPY --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/extracted/application/ ./

USER geosentinel:geosentinel

EXPOSE 8080

# Flag JVM per container: heap proporzionato al limite di memoria del cgroup.
# Niente -Djava.security.egd: da Java 9 SecureRandom non si blocca più su /dev/random
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+ExitOnOutOfMemoryError \
               -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]