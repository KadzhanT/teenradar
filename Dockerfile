FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# gradlew may be checked out with Windows line endings
RUN sed -i 's/\r$//' gradlew && sh gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN sh gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
# Render free tier: 512 MB RAM, a fraction of a CPU. Small heap, serial GC, C1-only JIT for a faster start.
ENV JAVA_TOOL_OPTIONS="-Xmx300m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
