# Backend Spring Boot: build multi-stage (JDK 21 per compilare, JRE 21 per eseguire).

# ---- 1. build -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Prima solo pom + wrapper: le dipendenze si scaricano in un layer separato, che Docker riusa finché
# il pom non cambia (le modifiche al codice non riscaricano mezzo Internet).
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# I test girano a parte (servono PostgreSQL con pgvector): qui si costruisce solo il jar.
RUN ./mvnw -B -q -DskipTests package && mv target/eaa-springboot-*.jar /build/app.jar

# ---- 2. runtime -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/app.jar app.jar

EXPOSE 8080
# MaxRAMPercentage: la JVM rispetta il limite di memoria del container invece di prendersi tutta la RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
