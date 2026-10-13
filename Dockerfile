# 입금콕: React 빌드 + Spring Boot 를 jar 하나로 묶은 이미지.
# docker build -t depositcock .
# docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... \
#   -e APP_JWT_SECRET=... -e APP_ADMIN_EMAIL=... -e APP_ADMIN_PASSWORD=... depositcock

FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY backend/src src
COPY --from=frontend /app/frontend/dist src/main/resources/static
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=backend /app/backend/target/depositcock-*.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
