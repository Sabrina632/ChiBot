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

# yt-dlp + plugin de poToken (bgutil): extrai a URL direta do audio do YouTube
# passando pela verificacao anti-bot. O plugin pede o token pro container
# bgutil-provider (veja docker-compose.yml). Instalado via pip num venv pra
# nao brigar com o python do sistema; atualiza junto com o rebuild da imagem.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv \
 && python3 -m venv /opt/yt-dlp \
 && /opt/yt-dlp/bin/pip install --no-cache-dir -U yt-dlp bgutil-ytdlp-pot-provider \
 && ln -s /opt/yt-dlp/bin/yt-dlp /usr/local/bin/yt-dlp \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copia a distribuicao gerada no estagio de build.
COPY --from=builder /build/build/install/ChiBot /app/ChiBot

# Diretorio de dados gravavel pelo usuario do bot (banco do Party Finder).
# O /app pertence ao root; sem isto o usuario chibot nao conseguiria escrever.
# Montado como volume no docker-compose.yml pra persistir entre recriacoes.
RUN mkdir -p /app/data && chown -R chibot:chibot /app/data
ENV CHIBOT_DB_PATH=/app/data/ChiData.db

USER chibot

# O bot le o ChiConfig.json a partir do diretorio de trabalho (/app).
# Monte esse arquivo como volume (veja docker-compose.yml).
ENTRYPOINT ["/app/ChiBot/bin/ChiBot"]