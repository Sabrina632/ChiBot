# ─── Estagio 1: build ──────────────────────────────────────────────
# Compila o projeto e gera a distribuicao (script + libs) via plugin "application".
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /build

# Copia primeiro os arquivos do Gradle pra aproveitar o cache de camadas:
# enquanto eles nao mudarem, o download do Gradle/dependencias fica cacheado.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./

# Baixa o Gradle do wrapper (camada cacheavel).
RUN chmod +x gradlew && ./gradlew --no-daemon --version

# Agora o codigo-fonte.
COPY src ./src

# installDist gera build/install/ChiBot/{bin,lib} com tudo que precisa pra rodar.
RUN ./gradlew --no-daemon clean installDist

# ─── Estagio 2: runtime ────────────────────────────────────────────
# Imagem so com o JRE (bem menor) pra rodar o bot.
FROM eclipse-temurin:17-jre AS runtime

# Roda como usuario sem privilegios.
RUN useradd --system --create-home --shell /usr/sbin/nologin chibot

WORKDIR /app

# Copia a distribuicao gerada no estagio de build.
COPY --from=builder /build/build/install/ChiBot /app/ChiBot

USER chibot

# O bot le o ChiConfig.json a partir do diretorio de trabalho (/app).
# Monte esse arquivo como volume (veja docker-compose.yml).
ENTRYPOINT ["/app/ChiBot/bin/ChiBot"]