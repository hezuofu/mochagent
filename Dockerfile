FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY target/*.jar mochaagent.jar

ENV JAVA_OPTS="-Xmx512m -Xms128m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar mochaagent.jar"]
