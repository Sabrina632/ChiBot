package org.chibot.Harem;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emojis customizados do harem, no estilo Mudae: a cor do kakera varia conforme
 * o valor do personagem (roxo → azul → ciano → verde → amarelo → laranja →
 * vermelho → arco-iris).
 *
 * <p>Os emojis sao <em>application emojis</em> da propria aplicacao do bot
 * (funcionam em qualquer servidor onde o ChiBot esta). No boot, o
 * {@link #sync(JDA)} sobe automaticamente os PNGs que estao em
 * {@code src/main/resources/emojis/<nome>.png} e ainda nao existem na aplicacao
 * — entao basta soltar os arquivos e fazer o redeploy. Enquanto um emoji nao
 * existir, cai no unicode {@code 💎}/{@code 💗}, sem quebrar nada.
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
        return List.copyOf(nomes);
    }

    /** Mapa nome → emoji carregado da aplicacao (vazio ate o sync rodar). */
    private static volatile Map<String, ApplicationEmoji> carregados = Map.of();

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
        }, err -> log.warn("Falha ao carregar os application emojis do harem.", err));
    }

    private static Icon lerIcon(String nome) {
        try (InputStream in = HaremEmojis.class.getResourceAsStream("/emojis/" + nome + ".png")) {
            return in == null ? null : Icon.from(in);
        } catch (IOException e) {
            log.warn("Erro ao ler o PNG do emoji '{}'.", nome, e);
            return null;
        }
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