package org.chibot.Harem;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.chibot.Database.HaremRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * Emojis customizados do harem, no estilo Mudae: a cor do kakera varia conforme
 * o valor do personagem (roxo → azul → ciano → verde → amarelo → laranja →
 * vermelho → arco-iris).
 *
 * <p>Os emojis sao <em>application emojis</em> da propria aplicacao do bot
 * (funcionam em qualquer servidor onde o ChiBot esta). No boot, o
 * {@link #sync(JDA)} sobe automaticamente os PNGs que estao em
 * {@code src/main/resources/emojis/<nome>.png} e ainda nao existem na aplicacao
 * — entao basta soltar os arquivos e fazer o redeploy. Os badges de personagem
 * ({@code badge_<id>}) sao um caso a parte: a arte e baixada do AniList e
 * recortada na hora (veja {@link #syncImagens}), sem precisar de PNG. Enquanto
 * um emoji nao existir, cai no fallback unicode, sem quebrar nada.
 */
public final class HaremEmojis {

    private HaremEmojis() {
    }

    private static final Logger log = LoggerFactory.getLogger(HaremEmojis.class);

    /** Fallback unicode usado quando o emoji customizado ainda nao foi carregado. */
    private static final String KAKERA_FALLBACK = "💎";

    /** Nome do emoji generico de kakera (saldos, custos, daily) — tom roxo, como o Mudae. */
    private static final String KAKERA = "kakera";

    /** Faixa de valor (limite superior inclusivo) → nome do emoji da cor. */
    private record Tier(int max, String name) {
    }

    /** Tabela de cores por valor de kakera (o range do AniList vai de 15 a 1200). */
    private static final List<Tier> TIERS = List.of(
            new Tier(149, KAKERA),       // roxo (mesmo do saldo)
            new Tier(299, "kakera_b"),   // azul
            new Tier(449, "kakera_c"),   // ciano
            new Tier(599, "kakera_g"),   // verde
            new Tier(749, "kakera_y"),   // amarelo
            new Tier(899, "kakera_o"),   // laranja
            new Tier(1049, "kakera_r"),  // vermelho
            new Tier(Integer.MAX_VALUE, "kakera_w")); // arco-iris

    /** Emoji da torre por nivel (0..TORRE_MAX) — badges do Mudae. */
    private static final String[] TORRE_NOMES = {
            "torre_bronze", "torre_silver", "torre_gold", "torre_emerald",
            "torre_sapphire", "torre_ruby", "torre_diamond"};

    /** Fallback unicode da torre por nivel, na mesma ordem de {@link #TORRE_NOMES}. */
    private static final String[] TORRE_FALLBACK = {"🌱", "🥉", "🥈", "🥇", "💠", "🔱", "💎"};

    /** Todos os nomes esperados (pro sync saber o que procurar nos resources). */
    private static final List<String> NOMES = montarNomes();

    private static List<String> montarNomes() {
        List<String> nomes = new java.util.ArrayList<>(List.of(
                KAKERA, "kakera_b", "kakera_c", "kakera_g",
                "kakera_y", "kakera_o", "kakera_r", "kakera_w"));
        nomes.addAll(List.of(TORRE_NOMES));
        nomes.addAll(HaremBadges.emojiNames());
        return List.copyOf(nomes);
    }

    /** Mapa nome → emoji carregado da aplicacao (vazio ate o sync rodar). */
    private static volatile Map<String, ApplicationEmoji> carregados = Map.of();

    /** Cliente HTTP pra baixar as imagens dos badges de personagem (AniList). */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Carrega os application emojis ja existentes e sobe os que faltam a partir
     * dos PNGs em {@code /emojis/}. Idempotente: nunca duplica um emoji que ja
     * existe. Deve ser chamado uma vez, depois do {@code awaitReady()}.
     */
    public static void sync(JDA jda) {
        jda.retrieveApplicationEmojis().queue(existentes -> {
            Map<String, ApplicationEmoji> mapa = new ConcurrentHashMap<>();
            for (ApplicationEmoji e : existentes) {
                mapa.put(e.getName(), e);
            }
            carregados = mapa;

            int subindo = 0;
            for (String nome : NOMES) {
                if (mapa.containsKey(nome)) {
                    continue;
                }
                Icon icon = lerIcon(nome);
                if (icon == null) {
                    continue; // sem PNG pra esse nome → segue no fallback unicode
                }
                subindo++;
                jda.createApplicationEmoji(nome, icon).queue(
                        criado -> {
                            mapa.put(criado.getName(), criado);
                            log.info("Emoji de harem '{}' criado.", nome);
                        },
                        err -> log.warn("Falha ao criar o emoji de harem '{}'.", nome, err));
            }
            log.info("Emojis de harem: {} ja existiam, {} sendo enviados.",
                    mapa.size(), subindo);

            // Quando a versao da arte muda (troquei os emblemas), recria eles uma
            // vez — assim o redeploy ja atualiza sem precisar apagar na mao.
            HaremService svc = HaremService.get();
            HaremRepository repo = svc != null ? svc.getRepo() : null;
            boolean refresh = repo != null && !ART_VERSION.equals(repo.getMeta(META_ART));

            // Badges com arte da rede (personagens via AniList, emblemas via
            // emoji.gg): HTTP bloqueante, entao roda fora da thread do JDA.
            syncImagens(jda, mapa, refresh, repo);
        }, err -> log.warn("Falha ao carregar os application emojis do harem.", err));
    }

    /** Versao da arte dos emblemas — bumpar isso forca a recriacao no proximo boot. */
    private static final String ART_VERSION = "2026-06-emojigg-1";
    private static final String META_ART = "badge_art_version";

    /** Base do pacote OpenMoji (CC BY-SA) no jsDelivr — fallback de arte dos emblemas. */
    private static final String OPENMOJI =
            "https://cdn.jsdelivr.net/gh/hfg-gmuend/openmoji@master/color/618x618/";

    /**
     * Arte fofa escolhida a dedo no emoji.gg pra cada emblema (conquista/loja).
     * Quando um emblema esta aqui, usa essa imagem; senao cai no OpenMoji. As
     * URLs do CDN do emoji.gg sao estaveis.
     */
    private static final Map<String, String> ARTE_EMBLEMA = Map.ofEntries(
            Map.entry("badge_sakura", "https://cdn3.emoji.gg/emojis/832263-purplesakuraflower.png"),
            Map.entry("badge_morango", "https://cdn3.emoji.gg/emojis/368854-fruitystrawberry.png"),
            Map.entry("badge_gatinho", "https://cdn3.emoji.gg/emojis/606294-calicocat.png"),
            Map.entry("badge_coracao", "https://cdn3.emoji.gg/emojis/543441-pinktriplehearts.png"),
            Map.entry("badge_estrela", "https://cdn3.emoji.gg/emojis/111020-chalkstars.png"),
            Map.entry("badge_laco", "https://cdn3.emoji.gg/emojis/930532-ribbonpink.png"),
            Map.entry("badge_luar", "https://cdn3.emoji.gg/emojis/984953-pixelmoon.png"),
            Map.entry("badge_borboleta", "https://cdn3.emoji.gg/emojis/788725-crystalbutterfly.png"),
            Map.entry("badge_arcoiris", "https://cdn3.emoji.gg/emojis/221251-pastelrainbow.png"),
            Map.entry("badge_primeiro_amor", "https://cdn3.emoji.gg/emojis/570002-chalkheartduo.png"),
            Map.entry("badge_colecionador", "https://cdn3.emoji.gg/emojis/887448-trihearts.png"),
            Map.entry("badge_mestre_harem", "https://cdn3.emoji.gg/emojis/505000-pastelcastle.png"),
            Map.entry("badge_lenda_harem", "https://cdn3.emoji.gg/emojis/143879-pastelshootingstar.png"),
            Map.entry("badge_tesouro_vivo", "https://cdn3.emoji.gg/emojis/672431-pinkdiamond.png"),
            Map.entry("badge_ricaco", "https://cdn3.emoji.gg/emojis/257090-bagofrubles.png"),
            Map.entry("badge_topo_torre", "https://cdn3.emoji.gg/emojis/9892-castle.png"),
            Map.entry("badge_sonhador", "https://cdn3.emoji.gg/emojis/971460-simplesparkles.png"),
            Map.entry("badge_realeza", "https://cdn3.emoji.gg/emojis/435134-chalkcrown.png"));

    /**
     * Arte fofa do emoji.gg pros enfeites do comando {@code profile} (titulos dos
     * campos). O kakera fica de fora de proposito — esse continua sendo o emoji
     * roxo proprio. Acessada via {@link #custom(String, String)} no ProfileCommand.
     */
    private static final Map<String, String> ARTE_PERFIL = Map.ofEntries(
            Map.entry("prof_harem", "https://cdn3.emoji.gg/emojis/35822-heart-rings.png"),
            Map.entry("prof_rank", "https://cdn3.emoji.gg/emojis/59686-trophy.png"),
            Map.entry("prof_torre", "https://cdn3.emoji.gg/emojis/7037-cute-pink-castle.png"),
            Map.entry("prof_wish", "https://cdn3.emoji.gg/emojis/11240-wishbottle.png"),
            Map.entry("prof_date", "https://cdn3.emoji.gg/emojis/956263-calendar.png"),
            Map.entry("prof_badges", "https://cdn3.emoji.gg/emojis/878925-medalstaff.png"),
            Map.entry("prof_fav", "https://cdn3.emoji.gg/emojis/18875-chalkheartred.png"));

    /**
     * Cria, numa thread daemon propria, os emojis dos badges que tem arte vinda
     * da rede e ainda nao existem: personagens (rosto do AniList) e os emblemas
     * de conquista/lojinha (arte fofa do emoji.gg via {@link #ARTE_EMBLEMA}, com
     * OpenMoji de fallback). Idempotente — pula os ja criados e os que tem PNG
     * local (esses sobem pelo loop principal).
     */
    private static void syncImagens(JDA jda, Map<String, ApplicationEmoji> mapa,
                                    boolean refresh, HaremRepository repo) {
        List<HaremBadges.Badge> personagens = HaremBadges.personagens();
        List<HaremBadges.Badge> emblemas = new ArrayList<>(HaremBadges.conquistas());
        emblemas.addAll(HaremBadges.loja());
        boolean faltam = personagens.stream().anyMatch(b -> !mapa.containsKey(b.emojiNome()))
                || emblemas.stream().anyMatch(b -> !mapa.containsKey(b.emojiNome())
                        && lerIcon(b.emojiNome()) == null)
                || ARTE_PERFIL.keySet().stream().anyMatch(n -> !mapa.containsKey(n) && lerIcon(n) == null);
        if (!refresh && !faltam) {
            return;
        }
        Thread worker = new Thread(() -> {
            // Refresh: apaga os emblemas atuais (sem PNG local) pra recriar com a
            // arte nova. Roda nesta thread, entao pode bloquear no complete().
            if (refresh) {
                for (HaremBadges.Badge b : emblemas) {
                    String nome = b.emojiNome();
                    ApplicationEmoji e = mapa.get(nome);
                    if (e == null || lerIcon(nome) != null) {
                        continue; // nao existe, ou tem PNG local (respeita)
                    }
                    try {
                        e.delete().complete();
                        mapa.remove(nome);
                        log.info("Emoji de emblema '{}' apagado pra recriar com a arte nova.", nome);
                    } catch (Exception ex) {
                        log.warn("Falha ao apagar o emblema '{}'.", nome, ex);
                    }
                }
                for (String nome : ARTE_PERFIL.keySet()) {
                    ApplicationEmoji e = mapa.get(nome);
                    if (e == null || lerIcon(nome) != null) {
                        continue;
                    }
                    try {
                        e.delete().complete();
                        mapa.remove(nome);
                        log.info("Emoji de perfil '{}' apagado pra recriar com a arte nova.", nome);
                    } catch (Exception ex) {
                        log.warn("Falha ao apagar o emoji de perfil '{}'.", nome, ex);
                    }
                }
            }

            AniListClient aniList = new AniListClient();

            int rostos = 0;
            for (HaremBadges.Badge b : personagens) {
                String nome = b.emojiNome();
                if (mapa.containsKey(nome)) {
                    continue;
                }
                try {
                    AnimeCharacter ch = aniList.searchCharacter(b.busca());
                    if (ch == null) {
                        log.warn("Badge '{}': AniList nao achou '{}'.", b.id(), b.busca());
                        continue;
                    }
                    if (criarEmoji(jda, mapa, nome, iconFromUrl(ch.imageUrl()),
                            "personagem '" + nome + "' (" + ch.name() + ")")) {
                        rostos++;
                        Thread.sleep(400); // pacing pro AniList e pro rate limit de emoji
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.warn("Falha ao montar o badge de personagem '{}'.", b.id(), ex);
                }
            }

            int emblemasCriados = 0;
            for (HaremBadges.Badge b : emblemas) {
                String nome = b.emojiNome();
                if (mapa.containsKey(nome) || lerIcon(nome) != null) {
                    continue; // ja existe ou tem PNG local (loop principal cuida)
                }
                try {
                    String url = ARTE_EMBLEMA.getOrDefault(nome, openmojiUrl(b.fallback()));
                    if (url != null && criarEmoji(jda, mapa, nome, iconFromUrl(url),
                            "emblema '" + nome + "' (emoji.gg)")) {
                        emblemasCriados++;
                        Thread.sleep(300);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.warn("Falha ao montar o emblema '{}'.", b.id(), ex);
                }
            }

            int perfilCriados = 0;
            for (Map.Entry<String, String> entrada : ARTE_PERFIL.entrySet()) {
                String nome = entrada.getKey();
                if (mapa.containsKey(nome) || lerIcon(nome) != null) {
                    continue;
                }
                try {
                    if (criarEmoji(jda, mapa, nome, iconFromUrl(entrada.getValue()),
                            "perfil '" + nome + "' (emoji.gg)")) {
                        perfilCriados++;
                        Thread.sleep(300);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.warn("Falha ao montar o emoji de perfil '{}'.", nome, ex);
                }
            }

            log.info("Badges com arte da rede: {} personagens (AniList) + {} emblemas + {} perfil (emoji.gg).",
                    rostos, emblemasCriados, perfilCriados);
            if (refresh && repo != null) {
                repo.setMeta(META_ART, ART_VERSION);
            }
        }, "harem-badge-emojis");
        worker.setDaemon(true);
        worker.start();
    }

    /** Cria um application emoji a partir de um {@link Icon} (no-op se o icon for null). */
    private static boolean criarEmoji(JDA jda, Map<String, ApplicationEmoji> mapa,
                                      String nome, Icon icon, String rotulo) {
        if (icon == null) {
            return false;
        }
        jda.createApplicationEmoji(nome, icon).queue(
                e -> {
                    mapa.put(e.getName(), e);
                    log.info("Emoji de {} criado.", rotulo);
                },
                err -> log.warn("Falha ao criar o emoji de {}.", rotulo, err));
        return true;
    }

    /**
     * URL da arte do OpenMoji pro emoji unicode dado (ex.: {@code 🌸} → .../1F338.png).
     * Junta os codepoints em hex maiusculo com {@code -}, ignorando o seletor de
     * variacao {@code U+FE0F}. Null se a string vier vazia.
     */
    private static String openmojiUrl(String emoji) {
        StringBuilder code = new StringBuilder();
        int i = 0;
        while (i < emoji.length()) {
            int cp = emoji.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F) {
                continue; // seletor de variacao nao entra no nome do arquivo
            }
            if (code.length() > 0) {
                code.append('-');
            }
            code.append(String.format("%04X", cp));
        }
        return code.length() == 0 ? null : OPENMOJI + code + ".png";
    }

    /** Baixa a imagem da URL e devolve um {@link Icon} quadrado (ou null se falhar). */
    private static Icon iconFromUrl(String url) {
        try {
            HttpResponse<byte[]> resp = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return null;
            }
            byte[] png = paraQuadradoPng(resp.body());
            return Icon.from(png != null ? png : resp.body());
        } catch (Exception e) {
            log.warn("Falha ao baixar a imagem do badge ({}).", url, e);
            return null;
        }
    }

    /** Recorta no centro pra um quadrado e reduz pra 160x160 PNG (null se nao der). */
    private static byte[] paraQuadradoPng(byte[] origem) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(origem));
            if (src == null) {
                return null;
            }
            int lado = Math.min(src.getWidth(), src.getHeight());
            int x = (src.getWidth() - lado) / 2;
            int y = (src.getHeight() - lado) / 2;
            BufferedImage quadrado = src.getSubimage(x, y, lado, lado);

            int destino = 160;
            BufferedImage out = new BufferedImage(destino, destino, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(quadrado, 0, 0, destino, destino, null);
            g.dispose();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static Icon lerIcon(String nome) {
        try (InputStream in = HaremEmojis.class.getResourceAsStream("/emojis/" + nome + ".png")) {
            return in == null ? null : Icon.from(in);
        } catch (IOException e) {
            log.warn("Erro ao ler o PNG do emoji '{}'.", nome, e);
            return null;
        }
    }

    /**
     * Application emoji pelo nome ({@code <:nome:id>}) ou o {@code fallback}
     * unicode se ele ainda nao foi carregado/criado. Usado pelos badges.
     */
    public static String custom(String nome, String fallback) {
        ApplicationEmoji e = carregados.get(nome);
        return e != null ? e.getFormatted() : fallback;
    }

    /** Kakera generico (saldos, custos, daily): {@code <:kakera:id>} ou {@code 💎}. */
    public static String kakera() {
        ApplicationEmoji e = carregados.get(KAKERA);
        return e != null ? e.getFormatted() : KAKERA_FALLBACK;
    }

    /** Kakera colorido pela faixa de valor do personagem (estilo Mudae). */
    public static String kakera(int valor) {
        ApplicationEmoji e = carregados.get(nomeDaFaixa(valor));
        return e != null ? e.getFormatted() : KAKERA_FALLBACK;
    }

    /** Versao {@link Emoji} do kakera colorido, pra usar em botao. */
    public static Emoji kakeraEmoji(int valor) {
        ApplicationEmoji e = carregados.get(nomeDaFaixa(valor));
        return e != null ? e : Emoji.fromUnicode(KAKERA_FALLBACK);
    }

    private static String nomeDaFaixa(int valor) {
        for (Tier t : TIERS) {
            if (valor <= t.max()) {
                return t.name();
            }
        }
        return KAKERA;
    }

    /** Emoji do nivel da torre (badge do Mudae): {@code <:torre_x:id>} ou o fallback unicode. */
    public static String torre(int nivel) {
        int i = Math.max(0, Math.min(TORRE_NOMES.length - 1, nivel));
        ApplicationEmoji e = carregados.get(TORRE_NOMES[i]);
        return e != null ? e.getFormatted() : TORRE_FALLBACK[i];
    }
}