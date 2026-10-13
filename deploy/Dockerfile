# 운영 이미지: 로컬에서 만든 jar만 복사한다 (서버에서 Gradle 빌드를 하지 않아 t3.small 메모리를 아낀다).
# jar는 CPU 구조와 무관하므로 Mac(arm64)에서 빌드해 EC2(x86_64)에서 그대로 실행된다.
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --home /app cci
WORKDIR /app
COPY app.jar /app/app.jar
USER cci

# 최대 힙 = 서버 메모리의 50% (t3.small 2GB → 약 1GB, t3.medium 4GB → 약 2GB). 나머지는 PostgreSQL·Caddy·OS. 인스턴스 유형을 바꿔도 자동으로 맞춰진다.
ENV JAVA_OPTS="-XX:InitialRAMPercentage=10 -XX:MaxRAMPercentage=50 -XX:+UseG1GC -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
