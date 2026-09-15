FROM eclipse-temurin:8-jre
WORKDIR /app
COPY target/hmdp-1.0-SNAPSHOT.jar app.jar
ENV TZ=Asia/Shanghai
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
