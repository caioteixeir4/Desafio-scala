FROM eclipse-temurin:21-jdk

ARG SBT_VERSION=1.10.7

# Instala o sbt a partir do release oficial
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
       | tar -xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Resolve dependencias primeiro para aproveitar o cache de camadas
COPY build.sbt ./
COPY project/build.properties project/
RUN sbt -batch update

COPY . .

CMD ["sbt", "-batch", "compile"]
