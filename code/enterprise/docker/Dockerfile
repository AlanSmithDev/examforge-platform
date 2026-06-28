# 多阶段构建：构建指定模块 → 运行镜像
# 用法（由 docker-compose 传入 MODULE）：docker build --build-arg MODULE=examforge-user ..
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
ARG MODULE
COPY pom.xml .
COPY examforge-common/pom.xml examforge-common/
COPY examforge-api/pom.xml examforge-api/
RUN mvn -q -pl examforge-common,examforge-api -am dependency:go-offline
COPY examforge-common examforge-common
COPY examforge-api examforge-api
COPY examforge-gateway examforge-gateway
COPY examforge-user examforge-user
COPY examforge-question examforge-question
COPY examforge-paper examforge-paper
COPY examforge-admin examforge-admin
COPY examforge-trade examforge-trade
COPY examforge-practice examforge-practice
COPY examforge-ai examforge-ai
RUN mvn -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
ARG MODULE
COPY --from=build /src/${MODULE}/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
