package org.chibot.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.chibot.Commands.CommandContext;
import org.chibot.Database.HaremRepository;
import org.chibot.Music.MusicUi;
import org.chibot.Translation.TranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Sistema de waifu/husbando estilo Mudae: rolls sorteiam personagens reais de
 * anime (AniList) ou de jogos (Giant Bomb) e quem reagir (com qualquer emoji)
 * dentro da janela casa com o personagem — um dono por personagem por servidor.
 *
 * <p>O servico mantem pools de personagens pre-buscados por genero (pra cada
 * roll nao custar uma chamada de API). Os rolls livres ficam num mapa em
 * memoria ({@code rollsAbertos}) ate alguem reagir; como a janela e curta
 * (~45s), um restart so descarta rolls que ainda estavam abertos na hora. O
 * botao de kakera (personagem ja casado) carrega os dados no custom id
 * ({@code hkak:valor:expira}), entao esse sobrevive a restart.
 */
public class HaremService extends ListenerAdapter {

    public enum Genero { WAIFU, HUSBANDO, QUALQUER }

    public static final int ROLLS_POR_HORA = 10;
    /** Cota propria dos rolls de jogos (sem bonus de torre nem rolls comprados). */
    public static final int ROLLS_JOGO_POR_HORA = 10;
    public static final Duration INTERVALO_CLAIM = Duration.ofHours(3);
    public static final Duration JANELA_CLAIM = Duration.ofSeconds(45);
    public static final int MAX_DESEJOS = 5;

    public static final Duration INTERVALO_DAILY = Duration.ofHours(20);
    public static final int DAILY_BASE = 200;
    public static final int DAILY_SORTE = 100;
    public static final int DAILY_POR_NIVEL = 50;

    public static final int CUSTO_ROLL_EXTRA = 30;
    public static final Duration JANELA_TROCA = Duration.ofMinutes(2);

    public static final int TORRE_MAX = 6;
    /** Bonus de kakera por nivel da torre ao coletar o botao de kakera (em %). */
    public static final int SAQUE_POR_NIVEL = 15;

    /** Custo pra subir a torre pro nivel informado (dobra a cada nivel). */
    public static int custoTorre(int nivel) {
        return 200 << (nivel - 1);
    }

    private static final Color COR_LIVRE = MusicUi.KAWAII_PINK;
    private static final Color COR_CASADA = new Color(0xE67E22);
    private static final Color COR_RECEM_CASADA = new Color(0xFFD700);
    private static final int MAX_POOL = 400;

    private static final Logger log = LoggerFactory.getLogger(HaremService.class);
    private static volatile HaremService instance;

    private final HaremRepository repo;
    private final AniListClient aniList = new AniListClient();
    private final GiantBombClient giantBomb = new GiantBombClient();
    private final Random random = new Random();

    private final ArrayDeque<AnimeCharacter> waifus = new ArrayDeque<>();
    private final ArrayDeque<AnimeCharacter> husbandos = new ArrayDeque<>();
    private final ArrayDeque<AnimeCharacter> outros = new ArrayDeque<>();

    private final ArrayDeque<GameCharacter> gameWaifus = new ArrayDeque<>();
    private final ArrayDeque<GameCharacter> gameHusbandos = new ArrayDeque<>();
    private final ArrayDeque<GameCharacter> gameOutros = new ArrayDeque<>();

    /** Lock proprio dos pools de jogos: um travamento do Giant Bomb nao segura os rolls de anime. */
    private final Object gameLock = new Object();

    /**
     * Mensagens cujo kakera ja foi coletado (guarda contra clique duplo), com o
     * instante (epoch ms) em que o botao expira — assim a limpeza remove so as
     * entradas vencidas, sem reabrir botoes ainda validos.
     */
    private final java.util.Map<Long, Long> kakeraColetado = new ConcurrentHashMap<>();

    /** Dados de um personagem livre rolado, esperando alguem reagir pra casar. */
    private record Roll(long charId, String name, String series, String image, int kakera,
                        long expiraMs, String guildId, boolean game) {
    }

    /** Rolls livres por id da mensagem — a primeira reacao valida casa o personagem. */
    private final java.util.Map<Long, Roll> rollsAbertos = new ConcurrentHashMap<>();

    /**
     * Pares "mensagem:usuario" ja avisados de cooldown (evita spam de aviso),
     * com o instante em que o roll expira — pra limpeza remover so os vencidos.
     */
    private final java.util.Map<String, Long> cooldownAvisado = new ConcurrentHashMap<>();

    private HaremService(HaremRepository repo) {
        this.repo = repo;
    }

    /** Cria o singleton no boot (registrado como listener dos botoes no JDA). */
    public static HaremService init() {
        instance = new HaremService(new HaremRepository());
        return instance;
    }

    public static HaremService get() {
        return instance;
    }

    public HaremRepository getRepo() {
        return repo;
    }

    // ------------------------------------------------------------------ rolls

    /** Sorteia um personagem e posta o embed com o botao de claim (ou de kakera, se ja casado). */
    public void roll(CommandContext ctx, Genero genero) {
        ctx.deferReply();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        long hora = System.currentTimeMillis() / 3_600_000L;

        // A torre de kakera da +1 roll por hora a cada nivel.
        int maxRolls = ROLLS_POR_HORA + repo.getPlayer(guildId, userId).towerLevel();
        int restantes = repo.tryUseRoll(guildId, userId, hora, maxRolls);
        if (restantes < 0) {
            ctx.reply("Seus rolls acabaram~ pode rolar de novo " + relativo((hora + 1) * 3_600_000L)
                    + "! (｡•́︿•̀｡)");
            return;
        }

        AnimeCharacter ch = pickCharacter(genero);
        if (ch == null) {
            ctx.reply("Não consegui falar com o AniList agora... tenta de novo daqui a pouco? (；△；)");
            return;
        }
        postarRoll(ctx, ch.id(), ch.name(), ch.series(), ch.imageUrl(), ch.kakera(), false, restantes);
    }

    /** Sorteia um personagem de jogo (Giant Bomb) e posta o embed de claim. */
    public void rollGame(CommandContext ctx, Genero genero) {
        if (!giantBomb.isAvailable()) {
            ctx.reply("Os rolls de jogos não estão configurados aqui (falta a `GIANTBOMB_API_KEY`)~ (・_・;)");
            return;
        }
        ctx.deferReply();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        long hora = System.currentTimeMillis() / 3_600_000L;

        int restantes = repo.tryUseGameRoll(guildId, userId, hora, ROLLS_JOGO_POR_HORA);
        if (restantes < 0) {
            ctx.reply("Seus rolls de jogos acabaram~ pode rolar de novo "
                    + relativo((hora + 1) * 3_600_000L) + "! (｡•́︿•̀｡)");
            return;
        }

        GameCharacter ch = pickGameCharacter(genero);
        if (ch == null) {
            ctx.reply("Não consegui falar com o Giant Bomb agora... tenta de novo daqui a pouco? (；△；)");
            return;
        }
        postarRoll(ctx, ch.id(), ch.name(), ch.game(), ch.imageUrl(), ch.kakera(), true, restantes);
    }

    /** Monta e posta o embed do personagem sorteado (livre = casavel por reacao; casado = botao de kakera). */
    private void postarRoll(CommandContext ctx, long charId, String name, String origem,
                            String imageUrl, int kakera, boolean game, int restantes) {
        String guildId = ctx.getGuild().getId();
        long agora = System.currentTimeMillis();
        HaremRepository.Claim dona = repo.findOwner(guildId, charId);
        long expira = (agora + JANELA_CLAIM.toMillis()) / 1000L;

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(name)
                .setDescription((game ? "🎮 " : "") + origem + "\n\n" + HaremEmojis.kakera(kakera)
                        + " **" + kakera + "** kakera")
                .setImage(imageUrl);

        if (dona == null) {
            eb.setColor(COR_LIVRE)
                    .setFooter("💗 Reage com qualquer emoji pra casar! · " + restantes + " roll(s) restantes");
            String conteudo = null;
            List<String> desejantes = repo.findWishers(guildId, name.toLowerCase(Locale.ROOT));
            if (!desejantes.isEmpty()) {
                conteudo = "✨ " + desejantes.stream()
                        .map(id -> "<@" + id + ">")
                        .collect(Collectors.joining(" "))
                        + " — apareceu alguém da sua lista de desejos!";
            }
            // Sem botao: quem reagir primeiro (qualquer emoji) casa — ver onMessageReactionAdd.
            Roll roll = new Roll(charId, name, origem, imageUrl, kakera,
                    agora + JANELA_CLAIM.toMillis(), guildId, game);
            ctx.replyEmbedAndThen(conteudo, eb.build(), msg -> registrarRoll(msg.getIdLong(), roll));
        } else {
            int saque = saqueDe(kakera);
            eb.setColor(COR_CASADA)
                    .setFooter("💍 Pertence a " + dona.ownerName());
            Button botao = Button.of(ButtonStyle.SECONDARY, "hkak:" + saque + ":" + expira,
                    String.valueOf(saque), HaremEmojis.kakeraEmoji(kakera));
            ctx.replyEmbedWithButtons(null, eb.build(), List.of(botao));
        }
    }

    /** Registra um roll aberto pra ser casado por reacao, limpando os expirados de vez em quando. */
    private void registrarRoll(long messageId, Roll roll) {
        if (rollsAbertos.size() > 2000) {
            long agora = System.currentTimeMillis();
            rollsAbertos.values().removeIf(r -> agora > r.expiraMs());
        }
        rollsAbertos.put(messageId, roll);
    }

    /** Rolls que ainda sobram pro jogador (cota da hora + bonus comprados), sem consumir nenhum. */
    public int rollsRestantes(String guildId, String userId) {
        HaremRepository.Player p = repo.getPlayer(guildId, userId);
        long hora = System.currentTimeMillis() / 3_600_000L;
        int usados = p.rollsHour() == hora ? p.rollsUsed() : 0;
        return Math.max(0, ROLLS_POR_HORA + p.towerLevel() - usados) + p.bonusRolls();
    }

    /** Instante (epoch ms) em que o jogador pode casar de novo. */
    public long proximoClaimMs(String guildId, String userId) {
        return repo.getPlayer(guildId, userId).lastClaimMs() + INTERVALO_CLAIM.toMillis();
    }

    /** Rolls de jogos que ainda sobram pro jogador na hora atual, sem consumir nenhum. */
    public int gameRollsRestantes(String guildId, String userId) {
        HaremRepository.Player p = repo.getPlayer(guildId, userId);
        long hora = System.currentTimeMillis() / 3_600_000L;
        int usados = p.gameRollsHour() == hora ? p.gameRollsUsed() : 0;
        return Math.max(0, ROLLS_JOGO_POR_HORA - usados);
    }

    /** Instante (epoch ms) em que o jogador pode casar um personagem de jogo de novo. */
    public long proximoGameClaimMs(String guildId, String userId) {
        return repo.getPlayer(guildId, userId).gameLastClaimMs() + INTERVALO_CLAIM.toMillis();
    }

    private synchronized AnimeCharacter pickCharacter(Genero genero) {
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            AnimeCharacter ch = pollPool(genero);
            if (ch != null) {
                return ch;
            }
            refill();
        }
        return pollPool(genero);
    }

    private AnimeCharacter pollPool(Genero genero) {
        switch (genero) {
            case WAIFU:
                return waifus.poll();
            case HUSBANDO:
                return husbandos.poll();
            default:
                int total = waifus.size() + husbandos.size() + outros.size();
                if (total == 0) {
                    return null;
                }
                int r = random.nextInt(total);
                if (r < waifus.size()) {
                    return waifus.poll();
                }
                return r < waifus.size() + husbandos.size() ? husbandos.poll() : outros.poll();
        }
    }

    /** Busca uma pagina aleatoria do AniList e distribui os personagens nos pools por genero. */
    private void refill() {
        int pagina = 1 + random.nextInt(AniListClient.MAX_PAGE);
        try {
            List<AnimeCharacter> lote = new ArrayList<>(aniList.fetchPage(pagina));
            Collections.shuffle(lote, random);
            for (AnimeCharacter ch : lote) {
                ArrayDeque<AnimeCharacter> pool =
                        ch.isFemale() ? waifus : ch.isMale() ? husbandos : outros;
                if (pool.size() < MAX_POOL) {
                    pool.add(ch);
                }
            }
        } catch (Exception e) {
            log.warn("Falha ao buscar personagens no AniList (pagina {}).", pagina, e);
        }
    }

    private GameCharacter pickGameCharacter(Genero genero) {
        synchronized (gameLock) {
            for (int tentativa = 0; tentativa < 3; tentativa++) {
                GameCharacter ch = pollGamePool(genero);
                if (ch != null) {
                    return ch;
                }
                refillGames();
            }
            return pollGamePool(genero);
        }
    }

    private GameCharacter pollGamePool(Genero genero) {
        switch (genero) {
            case WAIFU:
                return gameWaifus.poll();
            case HUSBANDO:
                return gameHusbandos.poll();
            default:
                int total = gameWaifus.size() + gameHusbandos.size() + gameOutros.size();
                if (total == 0) {
                    return null;
                }
                int r = random.nextInt(total);
                if (r < gameWaifus.size()) {
                    return gameWaifus.poll();
                }
                return r < gameWaifus.size() + gameHusbandos.size()
                        ? gameHusbandos.poll() : gameOutros.poll();
        }
    }

    /** Busca um offset aleatorio do Giant Bomb e distribui os personagens nos pools por genero. */
    private void refillGames() {
        int offset = random.nextInt(GiantBombClient.MAX_OFFSET / GiantBombClient.PER_PAGE + 1)
                * GiantBombClient.PER_PAGE;
        try {
            List<GameCharacter> lote = new ArrayList<>(giantBomb.fetchPage(offset));
            Collections.shuffle(lote, random);
            for (GameCharacter ch : lote) {
                ArrayDeque<GameCharacter> pool =
                        ch.isFemale() ? gameWaifus : ch.isMale() ? gameHusbandos : gameOutros;
                if (pool.size() < MAX_POOL) {
                    pool.add(ch);
                }
            }
        } catch (Exception e) {
            log.warn("Falha ao buscar personagens no Giant Bomb (offset {}).", offset, e);
        }
    }

    /** Kakera ganho ao clicar no botao de um personagem ja casado (~25% a 50% do valor). */
    private int saqueDe(int kakera) {
        return Math.max(5, kakera / 4 + random.nextInt(kakera / 4 + 1));
    }

    // ---------------------------------------------------------------- botoes

    // ----------------------------------------------------------------- troca

    /**
     * Posta a proposta de troca com os botoes de aceitar/recusar (so o dono do
     * personagem pedido pode aceitar). Os dados vao no custom id, e a posse dos
     * dois personagens e revalidada na hora do aceite.
     */
    public void proporTroca(CommandContext ctx, HaremRepository.Claim meu, HaremRepository.Claim dele) {
        long expira = (System.currentTimeMillis() + JANELA_TROCA.toMillis()) / 1000L;
        String base = ":" + meu.ownerId() + ":" + dele.ownerId()
                + ":" + meu.charId() + ":" + dele.charId() + ":" + expira;

        MessageEmbed embed = new EmbedBuilder()
                .setColor(COR_LIVRE)
                .setTitle("ﾟ･✧ Proposta de Troca ✧･ﾟ 🤝")
                .setDescription("**" + meu.ownerName() + "** oferece **" + meu.name()
                        + "** (" + HaremEmojis.kakera(meu.kakera()) + meu.kakera()
                        + ") em troca de **" + dele.name()
                        + "** (" + HaremEmojis.kakera(dele.kakera()) + dele.kakera()
                        + ") de <@" + dele.ownerId() + ">~")
                .setFooter("Só quem foi desafiado pode aceitar · expira em 2 minutos")
                .build();
        ctx.replyEmbedWithButtons("<@" + dele.ownerId() + ">", embed, List.of(
                Button.success("htrade" + base, "Aceitar 🤝"),
                Button.danger("htradeno" + base, "Recusar")));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        try {
            if (id.startsWith("hkak:")) {
                handleKakera(event, id);
            } else if (id.startsWith("htradeno:")) {
                handleTradeReject(event, id);
            } else if (id.startsWith("htrade:")) {
                handleTrade(event, id);
            }
        } catch (Exception e) {
            log.error("Erro ao processar o botao '{}'", id, e);
            responderEfemero(event, "Ops, algo deu errado~ (；△；)");
        }
    }

    /**
     * Casa o personagem de um roll livre quando alguem reage (com qualquer emoji)
     * dentro da janela. A primeira reacao valida vence: o {@code remove} do mapa
     * elege um unico ganhador por mensagem, e o {@link HaremRepository#tryClaim}
     * resolve corridas entre rolls diferentes do mesmo personagem.
     */
    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (!event.isFromGuild()
                || event.getUserIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            return;
        }
        long messageId = event.getMessageIdLong();
        Roll roll = rollsAbertos.get(messageId);
        if (roll == null) {
            return;
        }
        long agora = System.currentTimeMillis();
        if (agora > roll.expiraMs()) {
            rollsAbertos.remove(messageId);
            return;
        }
        String guildId = event.getGuild().getId();
        String userId = event.getUserId();

        // Claims de anime e de jogos tem cooldowns independentes.
        HaremRepository.Player p = repo.getPlayer(guildId, userId);
        long ultimoClaim = roll.game() ? p.gameLastClaimMs() : p.lastClaimMs();
        long proximoClaim = ultimoClaim + INTERVALO_CLAIM.toMillis();
        if (agora < proximoClaim) {
            // Em cooldown: o roll continua livre pra outra pessoa. Avisa a pessoa
            // (no maximo uma vez por roll, pra nao spammar o canal).
            if (cooldownAvisado.putIfAbsent(messageId + ":" + userId, roll.expiraMs()) == null) {
                if (cooldownAvisado.size() > 5000) {
                    cooldownAvisado.values().removeIf(expira -> agora > expira);
                }
                event.getChannel().sendMessage("<@" + userId + "> calminha, coração apressado~ "
                        + "você pode casar" + (roll.game() ? " (jogos)" : "") + " de novo "
                        + relativo(proximoClaim) + "! (・∀・)").queue();
            }
            return;
        }

        // Elege o ganhador da mensagem: so quem remover de fato segue adiante.
        if (rollsAbertos.remove(messageId) == null) {
            return;
        }

        event.retrieveMember().queue(
                membro -> finalizarClaim(event, roll, membro, agora),
                err -> log.warn("Falha ao buscar o membro que reagiu pra casar.", err));
    }

    private void finalizarClaim(MessageReactionAddEvent event, Roll roll, Member membro, long agora) {
        String guildId = roll.guildId();
        String userId = membro.getId();
        String nome = membro.getEffectiveName();

        HaremRepository.Claim claim = new HaremRepository.Claim(
                roll.charId(), roll.name(), roll.series(), roll.image(), roll.kakera(), userId, nome);
        if (!repo.tryClaim(guildId, claim, agora)) {
            // Outro roll do mesmo personagem casou antes: marca como pertencente ao dono.
            HaremRepository.Claim dona = repo.findOwner(guildId, roll.charId());
            if (dona != null) {
                editarRoll(event, roll, COR_CASADA, "💍 Pertence a " + dona.ownerName());
            }
            return;
        }

        if (roll.game()) {
            repo.setLastGameClaim(guildId, userId, agora);
        } else {
            repo.setLastClaim(guildId, userId, agora);
        }
        editarRoll(event, roll, COR_RECEM_CASADA, "💍 Pertence a " + nome);
        event.getChannel().sendMessage("💖 **" + nome + "** e **" + roll.name()
                + "** agora são casados! Que sejam felizes~ (´｡• ᵕ •｡`) ♡").queue();

        String aviso = anuncioBadges(nome, grantNewAchievements(guildId, userId));
        if (aviso != null) {
            event.getChannel().sendMessage(aviso).queue();
        }
    }

    /** Reescreve o embed do roll (mesma arte) com a cor/rodape de "casado". */
    private void editarRoll(MessageReactionAddEvent event, Roll roll, Color cor, String rodape) {
        MessageEmbed casado = new EmbedBuilder()
                .setTitle(roll.name())
                .setDescription((roll.game() ? "🎮 " : "") + roll.series() + "\n\n"
                        + HaremEmojis.kakera(roll.kakera()) + " **" + roll.kakera() + "** kakera")
                .setImage(roll.image())
                .setColor(cor)
                .setFooter(rodape)
                .build();
        event.getChannel().editMessageEmbedsById(event.getMessageId(), casado)
                .queue(ok -> {}, err -> {});
    }

    private void handleKakera(ButtonInteractionEvent event, String id) {
        if (!event.isFromGuild()) {
            return;
        }
        String[] partes = id.split(":");
        int saque = Integer.parseInt(partes[1]);
        long expiraSeg = Long.parseLong(partes[2]);

        if (System.currentTimeMillis() / 1000L > expiraSeg) {
            responderEfemero(event, "O kakera evaporou... mais sorte na próxima! (・_・;)");
            desabilitarBotao(event);
            return;
        }
        if (kakeraColetado.putIfAbsent(event.getMessageIdLong(), expiraSeg * 1000) != null) {
            responderEfemero(event, "Alguém pegou esse kakera primeiro~ (>_<)");
            return;
        }
        if (kakeraColetado.size() > 5000) {
            long agora = System.currentTimeMillis();
            kakeraColetado.values().removeIf(expira -> agora > expira);
        }

        String guildId = event.getGuild().getId();
        String userId = event.getUser().getId();
        // A torre de kakera aumenta o saque de quem clica.
        int nivel = repo.getPlayer(guildId, userId).towerLevel();
        int ganho = saque + saque * SAQUE_POR_NIVEL * nivel / 100;

        repo.addKakera(guildId, userId, ganho);
        event.editComponents(ActionRow.of(event.getButton().asDisabled())).queue();
        event.getChannel().sendMessage(HaremEmojis.kakera() + " **" + event.getUser().getEffectiveName()
                + "** coletou **" + ganho + "** kakera!"
                + (nivel > 0 ? " (bônus da torre " + HaremEmojis.torre(nivel) + ")" : "") + " ✧").queue();
    }

    private void handleTrade(ButtonInteractionEvent event, String id) {
        if (!event.isFromGuild()) {
            return;
        }
        String[] partes = id.split(":");
        String proponente = partes[1];
        String desafiado = partes[2];
        long charA = Long.parseLong(partes[3]);
        long charB = Long.parseLong(partes[4]);
        long expiraSeg = Long.parseLong(partes[5]);
        String guildId = event.getGuild().getId();

        if (!event.getUser().getId().equals(desafiado)) {
            responderEfemero(event, "Essa proposta não é pra você decidir~ (・∀・)");
            return;
        }
        if (System.currentTimeMillis() / 1000L > expiraSeg) {
            encerrarTroca(event, "A proposta expirou... propõe de novo! ⏰", COR_CASADA);
            return;
        }

        HaremRepository.Claim claimA = repo.findOwner(guildId, charA);
        HaremRepository.Claim claimB = repo.findOwner(guildId, charB);
        if (claimA == null || !claimA.ownerId().equals(proponente)
                || claimB == null || !claimB.ownerId().equals(desafiado)) {
            encerrarTroca(event, "A troca não vale mais — alguém se divorciou ou trocou antes~ (・_・;)", COR_CASADA);
            return;
        }

        boolean ok = repo.tradeClaims(guildId,
                charA, proponente, desafiado, event.getUser().getEffectiveName(),
                charB, desafiado, proponente, claimA.ownerName());
        if (!ok) {
            encerrarTroca(event, "Não consegui completar a troca~ (；△；)", COR_CASADA);
            return;
        }

        encerrarTroca(event, "Troca concluída! 🤝", COR_RECEM_CASADA);
        event.getChannel().sendMessage("🤝 **" + claimA.ownerName() + "** e **"
                + event.getUser().getEffectiveName() + "** trocaram **" + claimA.name()
                + "** por **" + claimB.name() + "**! Que todos sejam felizes~ (´｡• ᵕ •｡`) ♡").queue();
    }

    private void handleTradeReject(ButtonInteractionEvent event, String id) {
        if (!event.isFromGuild()) {
            return;
        }
        String[] partes = id.split(":");
        String proponente = partes[1];
        String desafiado = partes[2];
        String clicou = event.getUser().getId();
        if (!clicou.equals(proponente) && !clicou.equals(desafiado)) {
            responderEfemero(event, "Essa proposta não é sua pra recusar~ (・∀・)");
            return;
        }
        encerrarTroca(event, clicou.equals(proponente)
                ? "Proposta cancelada pelo próprio proponente~ 💔"
                : "Proposta recusada... 💔", COR_CASADA);
    }

    /** Atualiza o embed da proposta com o resultado e remove os botoes. */
    private void encerrarTroca(ButtonInteractionEvent event, String resultado, Color cor) {
        List<MessageEmbed> embeds = event.getMessage().getEmbeds();
        if (embeds.isEmpty()) {
            return;
        }
        event.editMessageEmbeds(new EmbedBuilder(embeds.get(0))
                        .setColor(cor)
                        .setFooter(resultado)
                        .build())
                .setComponents()
                .queue();
    }

    private void responderEfemero(ButtonInteractionEvent event, String texto) {
        if (!event.isAcknowledged()) {
            // Efemero: so quem clicou ve, entao da pra traduzir pro idioma dele.
            // (Os anuncios publicos do harem ficam em pt — sao pro canal inteiro.)
            String traduzido = TranslationService.forUser(event.getUser().getId(), texto);
            event.reply(traduzido).setEphemeral(true).queue();
        }
    }

    private void desabilitarBotao(ButtonInteractionEvent event) {
        event.getMessage()
                .editMessageComponents(ActionRow.of(event.getButton().asDisabled()))
                .queue(ok -> {}, err -> {});
    }

    // ---------------------------------------------------------------- badges

    /**
     * Confere as conquistas do jogador e concede os badges recem-desbloqueados,
     * retornando a lista deles (vazia se nada novo). Idempotente: um badge ja
     * possuido nunca e concedido de novo.
     */
    public List<HaremBadges.Badge> grantNewAchievements(String guildId, String userId) {
        HaremRepository.HaremStats st = repo.haremStats(guildId, userId);
        HaremRepository.Player p = repo.getPlayer(guildId, userId);
        HaremBadges.Stats stats = new HaremBadges.Stats(
                st.count(), st.valorTotal(), p.towerLevel(), p.kakera(),
                repo.listWishes(guildId, userId).size(), repo.haremRank(guildId, userId),
                repo.claimCharIds(guildId, userId));

        Set<String> owned = repo.ownedBadges(guildId, userId);
        long agora = System.currentTimeMillis();
        List<HaremBadges.Badge> novos = new ArrayList<>();
        for (HaremBadges.Badge b : HaremBadges.autoDesbloqueaveis()) {
            if (!owned.contains(b.id()) && b.desbloqueado(stats)
                    && repo.grantBadge(guildId, userId, b.id(), agora)) {
                novos.add(b);
            }
        }
        return novos;
    }

    /** Frase de anuncio dos badges recem-ganhos (ou null se a lista veio vazia). */
    public static String anuncioBadges(String nome, List<HaremBadges.Badge> novos) {
        if (novos.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("🎖️ **" + nome + "** desbloqueou ");
        sb.append(novos.size() == 1 ? "um novo badge: " : "novos badges: ");
        for (int i = 0; i < novos.size(); i++) {
            HaremBadges.Badge b = novos.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(b.emoji()).append(" **").append(b.nome()).append("**");
        }
        sb.append("! ✧");
        return sb.toString();
    }

    /** Timestamp relativo do Discord ("em 23 minutos"). */
    public static String relativo(long epochMs) {
        return "<t:" + epochMs / 1000L + ":R>";
    }
}