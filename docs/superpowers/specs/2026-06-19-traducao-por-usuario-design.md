# Tradução por usuário (i18n dinâmico via Amazon Translate)

**Data:** 2026-06-19
**Status:** Aprovado (design) — aguardando plano de implementação

## Objetivo

Permitir que cada usuário escolha o idioma em que a Chi responde **só pra ele**, com
`!language <código>`. Por padrão o bot continua em português; quem trocar o idioma
recebe as respostas traduzidas, sem afetar mais ninguém no servidor.

Exemplo: um usuário gringo manda `!language en` e, a partir daí, todas as respostas
do bot chegam em inglês **apenas pra ele** — os outros continuam vendo português.

## Decisão de abordagem

Tradução **automática via API** (Amazon Translate), e não catálogo de strings (i18n
estático). Motivos:

- Funciona pra qualquer idioma sem traduzir as ~247 mensagens à mão.
- Amazon Translate **não consome CPU da VPS** (que já é gargalada em CPU por causa
  do áudio/Lavalink) — a tradução roda na nuvem da AWS, diferente do LibreTranslate
  self-hospedado.
- Qualidade neural boa; com cache, o custo fica irrisório (provavelmente dentro do
  free tier).

## Arquitetura

### 1. Ponto de interceptação (o "pulo do gato")

Toda mensagem visível ao usuário sai por dois pontos: `PrefixCommandContext` e
`SlashCommandContext` (métodos `reply(...)` / `replyEmbeds(...)`). A tradução é
interceptada **nesses dois pontos**, então as ~247 mensagens espalhadas pelo código
**não precisam ser tocadas**.

Fluxo:

1. Antes de enviar, olha o idioma do autor (via `LanguageRepository`).
2. Se for `pt` (padrão), envia direto — **zero overhead** pra quem é brasileiro.
3. Se for outro idioma, passa o texto (e o conteúdo do embed) pelo `TranslationService`
   antes de enviar.

### 2. `LanguageRepository` (banco dedicado novo)

Banco **separado e novo**: `ChiLang.db` — dedicado ao sistema de tradução, à parte
do `ChiData.db` (harém/PF), `ChiMusic.db` (música) e `ChiState.db` (manutenção).
Segue o mesmo padrão de path dos outros (`CHIBOT_LANG_DB_PATH` sobrescreve; por
padrão fica ao lado do banco principal). Esse banco guarda **duas tabelas** do
sistema: a preferência de idioma por usuário e o cache de traduções.

Tabela de preferência:

```sql
CREATE TABLE IF NOT EXISTS user_language (
    user_id TEXT NOT NULL PRIMARY KEY,
    lang    TEXT NOT NULL
)
```

Tabela de cache (ver seção 3):

```sql
CREATE TABLE IF NOT EXISTS translation_cache (
    lang        TEXT NOT NULL,
    source_hash TEXT NOT NULL,   -- hash do texto original (pt)
    translated  TEXT NOT NULL,
    PRIMARY KEY (lang, source_hash)
)
```

API de preferência: `String getLanguage(String userId)` (retorna `pt` se não houver
registro), `void setLanguage(String userId, String lang)`.

API de cache: `String getCached(String lang, String sourceHash)` (ou `null`),
`void putCached(String lang, String sourceHash, String translated)`.

- Idioma é **por pessoa, global** (vale em qualquer servidor), não por servidor.
- Degrada com log se o banco não abrir (segue o mesmo espírito dos outros repos):
  sem banco, todo mundo fica em `pt` e o cache vira só em memória.

### 3. `TranslationService` (o coração)

Envolve o cliente do Amazon Translate (`software.amazon.awssdk:translate`).

`String translate(String texto, String idioma)`:

- Se `idioma == "pt"` → devolve o texto igual (no-op).
- **Máscara de emoticons:** antes de chamar a API, substitui cada emoticon kawaii
  por um marcador temporário que a API não mexe (ex.: `￿0￿`), guarda o
  mapeamento, traduz o texto, e restaura os emoticons originais na volta. Preserva a
  personalidade da Chi.
- **Cache em dois níveis:** memória + banco (`ChiLang.db`, tabela `translation_cache`).
  Ao traduzir, olha primeiro o cache em memória; se não tiver, olha o banco; se ainda
  não tiver, chama a API **uma vez** e grava nos dois. A chave é `(idioma, hash do
  texto original)`. Resultado: cada frase única é traduzida **uma vez na vida** (não
  uma vez por reinício) — o cache do banco sobrevive a restart/redeploy, derrubando o
  consumo da API e a latência pós-deploy. O nível em memória evita ler o banco a cada
  mensagem.
- **Degrada com log:** se a API falhar ou não houver credencial, devolve o texto
  original. O bot nunca quebra por causa da tradução.

### 4. Tradução de embeds

Como muitos `reply` são embeds, traduz `title`, `description` e os `fields`
(nome + valor) de cada embed. Footer e author ficam intactos.

### 5. `LanguageCommand` (`!language`)

- `!language <código>` → salva o idioma do autor e responde uma confirmação (já no
  novo idioma).
- `!language` (sem argumento) → mostra o idioma atual + a lista de suportados.
- `!language pt` → volta ao padrão (português).
- Valida o código contra uma **lista curada** de idiomas suportados (ex.: pt, en, es,
  ja, fr, de, it, ru, ko, zh). Código inválido = mensagem de erro com a lista.

### 6. Configuração / boot

- `ChiConfig` lê `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` e `AWS_REGION` do `.env`
  (mesmo padrão dos outros segredos; `.env` já está no `.gitignore`).
- No boot, o `LanguageRepository` (banco `ChiLang.db`) e o `TranslationService` são
  inicializados e injetados nos contextos de comando. Sem credencial = tradução
  desligada (degrada, tudo em `pt`).
- Dependência nova no `build.gradle`: `software.amazon.awssdk:translate`.
- `.gitignore` ganha `ChiLang.db` (+ `-wal`/`-shm`), como os outros bancos locais.
- No Docker, `ChiLang.db` cai ao lado do banco principal (`/app/data`, volume
  `chibot-data`), então persiste entre recriações do container sem mexer no compose.

## Tratamento de erros

- Banco indisponível → todo mundo em `pt` (degrada).
- API/credencial ausente ou com falha → devolve texto original + log de aviso.
- Código de idioma inválido no comando → erro amigável listando os suportados.

## Fora do escopo (v1)

- Mascarar **nomes de personagem** (Levi, Gojo, etc.) — extensão futura; v1 mascara
  só emoticons.
- Tradução por servidor (idioma compartilhado) — fora do escopo; é por usuário.

## Pré-requisito operacional

Credencial IAM da AWS com a política `TranslateReadOnly`, colocada no `.env` da VPS.
(A chave exposta no chat durante o brainstorming deve ser rotacionada antes de usar.)

## Testes

- `LanguageRepository`: padrão `pt`, set/get da preferência, get/put do cache,
  persistência da preferência **e** do cache entre "restarts" (reabrir o arquivo),
  degradação sem banco — espelhando o estilo dos testes de repositório existentes
  (SQLite em memória / arquivo temporário, com `close()` pra simular restart).
- `TranslationService`: no-op pra `pt`, máscara de emoticons (round-trip preserva os
  rostinhos), cache em memória (segunda chamada não bate na API), uso do cache do banco
  (frase já traduzida em execução anterior não bate na API), degradação sem credencial
  — com o cliente da AWS mockado/abstraído pra não chamar a rede no teste.