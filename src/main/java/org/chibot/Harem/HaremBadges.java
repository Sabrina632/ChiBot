package org.chibot.Harem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Catalogo de badges colecionaveis do harem, no estilo Mudae: emblemas que o
 * jogador junta e exibe no {@code profile}. Tres familias:
 *
 * <ul>
 *   <li><b>Conquista</b> — desbloqueia sozinho ao bater um marco (primeiro
 *       casamento, topo da torre, virar #1 do servidor...).</li>
 *   <li><b>Loja</b> — comprado com kakera, puramente decorativo.</li>
 *   <li><b>Personagem</b> — um personagem real de anime/manga; desbloqueia ao
 *       <em>casar</em> com ele no harem <b>ou</b> comprando com kakera. A arte
 *       e o rosto do personagem (puxado do AniList — veja {@link HaremEmojis}).</li>
 * </ul>
 *
 * <p>Cada badge tem um emoji: se existir um application emoji {@code badge_<id>},
 * usa ele; senao cai no fallback unicode, entao tudo funciona mesmo enquanto a
 * imagem nao subiu. Veja {@link HaremEmojis}.
 */
public final class HaremBadges {

    private HaremBadges() {
    }

    /** Quantos badges dao pra exibir ao mesmo tempo no perfil. */
    public static final int MAX_NO_PERFIL = 6;

    public enum Tipo { CONQUISTA, LOJA, PERSONAGEM }

    /** Numeros do jogador usados pra avaliar as conquistas. */
    public record Stats(int personagens, long valorTotal, int torre,
                        long kakera, int desejos, int rank, Set<String> nomesNoHarem) {

        /** Se o jogador tem no harem algum personagem cujo nome contem {@code chave}. */
        public boolean temPersonagem(String chave) {
            String c = chave.toLowerCase(Locale.ROOT);
            for (String nome : nomesNoHarem) {
                if (nome.contains(c)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Um badge do catalogo. */
    public static final class Badge {
        private final String id;
        private final String nome;
        private final String serie;          // so PERSONAGEM (rotulo); "" nos outros
        private final String busca;          // so PERSONAGEM (termo de busca no AniList)
        private final String fallback;
        private final String descricao;
        private final Tipo tipo;
        private final long preco;            // 0 = nao esta a venda
        private final Predicate<Stats> cond; // null = nao desbloqueia sozinho

        private Badge(String id, String nome, String serie, String busca, String fallback,
                      String descricao, Tipo tipo, long preco, Predicate<Stats> cond) {
            this.id = id;
            this.nome = nome;
            this.serie = serie;
            this.busca = busca;
            this.fallback = fallback;
            this.descricao = descricao;
            this.tipo = tipo;
            this.preco = preco;
            this.cond = cond;
        }

        public String id() {
            return id;
        }

        public String nome() {
            return nome;
        }

        public String serie() {
            return serie;
        }

        /** Termo de busca no AniList (so faz sentido pra badge de personagem). */
        public String busca() {
            return busca;
        }

        public String descricao() {
            return descricao;
        }

        public Tipo tipo() {
            return tipo;
        }

        public long preco() {
            return preco;
        }

        /** Da pra comprar com kakera? (todo badge com preco > 0). */
        public boolean compravel() {
            return preco > 0;
        }

        /** Nome do application emoji esperado ({@code badge_<id>}). */
        public String emojiNome() {
            return "badge_" + id;
        }

        /** Emoji pronto pra usar (custom da aplicacao ou o fallback unicode). */
        public String emoji() {
            return HaremEmojis.custom(emojiNome(), fallback);
        }

        /** Se a condicao de desbloqueio automatico ja foi atingida pelos stats. */
        public boolean desbloqueado(Stats s) {
            return cond != null && cond.test(s);
        }
    }

    private static Badge conquista(String id, String nome, String fallback,
                                   String descricao, Predicate<Stats> cond) {
        return new Badge(id, nome, "", null, fallback, descricao, Tipo.CONQUISTA, 0, cond);
    }

    private static Badge loja(String id, String nome, String fallback,
                              String descricao, long preco) {
        return new Badge(id, nome, "", null, fallback, descricao, Tipo.LOJA, preco, null);
    }

    /** Badge de personagem: desbloqueia casando com ele (match pelo nome) ou comprando. */
    private static Badge personagem(String id, String nome, String serie, String busca,
                                    String fallback, long preco) {
        return new Badge(id, nome, serie, busca, fallback, serie, Tipo.PERSONAGEM, preco,
                s -> s.temPersonagem(nome));
    }

    private static final List<Badge> CATALOGO = List.of(
            // ---- Conquistas (desbloqueiam por marcos) ----
            conquista("primeiro_amor", "Primeiro Amor", "💕",
                    "Case com o seu primeiro personagem.",
                    s -> s.personagens() >= 1),
            conquista("colecionador", "Colecionador", "💞",
                    "Tenha 10 personagens no harém.",
                    s -> s.personagens() >= 10),
            conquista("mestre_harem", "Mestre do Harém", "🏯",
                    "Tenha 50 personagens no harém.",
                    s -> s.personagens() >= 50),
            conquista("lenda_harem", "Lenda do Harém", "🌟",
                    "Tenha 100 personagens no harém.",
                    s -> s.personagens() >= 100),
            conquista("tesouro_vivo", "Tesouro Vivo", "💎",
                    "Acumule 25000 de valor total no harém.",
                    s -> s.valorTotal() >= 25000),
            conquista("ricaco", "Ricaço", "💰",
                    "Junte 10000 kakera no saldo.",
                    s -> s.kakera() >= 10000),
            conquista("topo_torre", "Topo da Torre", "🏰",
                    "Chegue ao topo da torre de kakera.",
                    s -> s.torre() >= HaremService.TORRE_MAX),
            conquista("sonhador", "Sonhador", "✨",
                    "Encha a sua lista de desejos.",
                    s -> s.desejos() >= HaremService.MAX_DESEJOS),
            conquista("realeza", "Realeza do Servidor", "👑",
                    "Seja o #1 do ranking do servidor.",
                    s -> s.rank() == 1),

            // ---- Loja (compradas com kakera, decorativas) ----
            loja("sakura", "Sakura", "🌸", "Uma florzinha de cerejeira.", 500),
            loja("morango", "Morango", "🍓", "Docinho e fofo.", 600),
            loja("gatinho", "Gatinho", "🐱", "Miau~", 700),
            loja("coracao", "Coração", "💖", "Cheio de amor.", 800),
            loja("estrela", "Estrelinha", "⭐", "Você brilha!", 1000),
            loja("laco", "Laço", "🎀", "Um laço bem kawaii.", 1200),
            loja("luar", "Luar", "🌙", "Sob a luz da lua.", 1500),
            loja("borboleta", "Borboleta", "🦋", "Voa, voa.", 1800),
            loja("arcoiris", "Arco-íris", "🌈", "Todas as cores do kakera.", 2500),

            // ---- Personagens (casar com o personagem ou comprar) ----
            personagem("rem", "Rem", "Re:Zero", "Rem", "💙", 1500),
            personagem("emilia", "Emilia", "Re:Zero", "Emilia", "💜", 1200),
            personagem("megumin", "Megumin", "KonoSuba", "Megumin", "💥", 1200),
            personagem("zerotwo", "Zero Two", "Darling in the FranXX", "Zero Two", "🦖", 1500),
            personagem("miku", "Hatsune Miku", "Vocaloid", "Hatsune Miku", "🎤", 1300),
            personagem("levi", "Levi", "Attack on Titan", "Levi Ackerman", "🗡️", 1500),
            personagem("mikasa", "Mikasa", "Attack on Titan", "Mikasa Ackerman", "🧣", 1300),
            personagem("naruto", "Naruto", "Naruto", "Naruto Uzumaki", "🍥", 1200),
            personagem("luffy", "Luffy", "One Piece", "Monkey D. Luffy", "👒", 1200),
            personagem("goku", "Goku", "Dragon Ball", "Son Goku", "🔥", 1200),
            personagem("gojo", "Gojo", "Jujutsu Kaisen", "Satoru Gojo", "🔵", 1500),
            personagem("nezuko", "Nezuko", "Demon Slayer", "Nezuko Kamado", "🎍", 1300),
            personagem("asuna", "Asuna", "Sword Art Online", "Asuna Yuuki", "⚔️", 1000),
            personagem("marin", "Marin", "My Dress-Up Darling", "Marin Kitagawa", "👗", 1300),
            personagem("makima", "Makima", "Chainsaw Man", "Makima", "🐶", 1400),
            personagem("violet", "Violet", "Violet Evergarden", "Violet Evergarden", "💌", 1100));

    private static final List<Badge> CONQUISTAS =
            CATALOGO.stream().filter(b -> b.tipo() == Tipo.CONQUISTA).toList();
    private static final List<Badge> LOJA =
            CATALOGO.stream().filter(b -> b.tipo() == Tipo.LOJA).toList();
    private static final List<Badge> PERSONAGENS =
            CATALOGO.stream().filter(b -> b.tipo() == Tipo.PERSONAGEM).toList();
    private static final List<Badge> AUTO =
            CATALOGO.stream().filter(b -> b.cond != null).toList();

    public static List<Badge> todos() {
        return CATALOGO;
    }

    public static List<Badge> conquistas() {
        return CONQUISTAS;
    }

    public static List<Badge> loja() {
        return LOJA;
    }

    public static List<Badge> personagens() {
        return PERSONAGENS;
    }

    /** Badges que desbloqueiam sozinhos quando a condicao bate (conquistas + personagens). */
    public static List<Badge> autoDesbloqueaveis() {
        return AUTO;
    }

    /** Badge pelo id exato, ou null. */
    public static Badge byId(String id) {
        for (Badge b : CATALOGO) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Acha um badge pelo termo digitado: tenta id/nome exato e, se nao bater,
     * aceita um unico match parcial pelo nome. Null se nao achar ou for ambiguo.
     */
    public static Badge find(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Badge b : CATALOGO) {
            if (b.id().equals(q) || b.nome().toLowerCase(Locale.ROOT).equals(q)) {
                return b;
            }
        }
        Badge unico = null;
        for (Badge b : CATALOGO) {
            if (b.nome().toLowerCase(Locale.ROOT).contains(q) || b.id().contains(q)) {
                if (unico != null) {
                    return null; // ambiguo
                }
                unico = b;
            }
        }
        return unico;
    }

    /** Nomes dos application emojis de todos os badges (pro sync do {@link HaremEmojis}). */
    public static List<String> emojiNames() {
        List<String> out = new ArrayList<>(CATALOGO.size());
        for (Badge b : CATALOGO) {
            out.add(b.emojiNome());
        }
        return out;
    }
}