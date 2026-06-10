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

# yt-dlp: extrai a URL direta do audio do YouTube. Instalado via pip num venv
# pra nao brigar com o python do sistema; atualiza junto com o rebuild da imagem.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv curl unzip ca-certificates \
 && python3 -m venv /opt/yt-dlp \
 && /opt/yt-dlp/bin/pip install --no-cache-dir -U yt-dlp \
 && ln -s /opt/yt-dlp/bin/yt-dlp /usr/local/bin/yt-dlp \
 # deno: runtime de JS que o yt-dlp usa pros desafios de assinatura dos
 # clients web (sem ele faltam formatos e a extracao esta deprecated).
 && curl -fsSL https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip \
      -o /tmp/deno.zip \
 && unzip -q /tmp/deno.zip -d /usr/local/bin \
 && chmod +x /usr/local/bin/deno \
 && rm /tmp/deno.zip \
 && apt-get purge -y curl unzip \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copia a distribuicao gerada no estagio de build.
COPY --from=builder /build/build/install/ChiBot /app/ChiBot

# Diretorio de dados gravavel pelo usuario do bot (banco do Party Finder).
# O /app pertence ao root; sem isto o usuario chibot nao conseguiria escrever.
# Montado como volume no docker-compose.yml pra persistir entre recriacoes.
RUN mkdir -p /app/data && chown -R chibot:chibot /app/data
ENV CHIBOT_DB_PATH=/app/data/ChiData.db
# Cache do yt-dlp (script EJS de desafios do YouTube) no volume persistente,
# pra nao precisar rebaixar a cada recriacao do container.
ENV XDG_CACHE_HOME=/app/data/cache

USER chibot

# O bot le o ChiConfig.json a partir do diretorio de trabalho (/app).
# Monte esse arquivo como volume (veja docker-compose.yml).
ENTRYPOINT ["/app/ChiBot/bin/ChiBot"]