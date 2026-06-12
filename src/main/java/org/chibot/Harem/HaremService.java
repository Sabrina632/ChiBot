package org.chibot.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.chibot.Commands.CommandContext;
import org.chibot.Database.HaremRepository;
import org.chibot.Music.MusicUi;
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
 * anime (AniList) e quem clicar no coracao dentro da janela casa com o
 * personagem — um dono por personagem por servidor.
 *
 * <p>O servico mantem pools de personagens pre-buscados por genero (pra cada
 * roll nao custar uma chamada de API) e escuta os botoes de claim/kakera. Os
 * custom ids carregam os dados do botao ({@code hclaim:charId:kakera:expira} e
 * {@code hkak:valor:expira}), entao um restart do bot nao quebra rolls antigos.
 */
public class HaremService extends ListenerAdapter {

    public enum Genero { WAIFU, HUSBANDO, QUALQUER }

    public static final int ROLLS_POR_HORA = 10;
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
    /** Bonus de kakera por nivel da torre ao coletar o botao 💎 (em %). */
    public static final int SAQUE_POR_NIVEL = 15;
    public static final String[] TORRE_EMOJIS = {"🌱", "🥉", "🥈", "🥇", "💠", "🔱", "💎"};

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
    private final Random random = new Random();

    private final ArrayDeque<AnimeCharacter> waifus = new ArrayDeque<>();
    private final ArrayDeque<AnimeCharacter> husbandos = new ArrayDeque<>();
    private final ArrayDeque<AnimeCharacter> outros = new ArrayDeque<>();

    /** Mensagens cujo kakera ja foi coletado (guarda contra clique duplo). */
    private final Set<Long> kakeraColetado = ConcurrentHashMap.newKeySet();

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
        long agora = System.currentTimeMillis();
        long hora = agora / 3_600_000L;

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

        HaremRepository.Claim dona = repo.findOwner(guildId, ch.id());
        long expira = (agora + JANELA_CLAIM.toMillis()) / 1000L;

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(ch.name())
                .setDescription(ch.series() + "\n\n💎 **" + ch.kakera() + "** kakera")
                .setImage(ch.imageUrl());

        String conteudo = null;
        List<Button> botoes = new ArrayList<>();
        if (dona == null) {
            eb.setColor(COR_LIVRE)
                    .setFooter("💗 Clica no coração pra casar! · " + restantes + " roll(s) restantes");
            botoes.add(Button.danger("hclaim:" + ch.id() + ":" + ch.kakera() + ":" + expira,
                    Emoji.fromUnicode("💗")));
            List<String> desejantes = repo.findWishers(guildId, ch.name().toLowerCase(Locale.ROOT));
            if (!desejantes.isEmpty()) {
                conteudo = "✨ " + desejantes.stream()
                        .map(id -> "<@" + id + ">")
                        .collect(Collectors.joining(" "))
                        + " — apareceu alguém da sua lista de desejos!";
            }
        } else {
            int saque = saqueDe(ch.kakera());
            eb.setColor(COR_CASADA)
                    .setFooter("💍 Pertence a " + dona.ownerName());
            botoes.add(Button.of(ButtonStyle.SECONDARY, "hkak:" + saque + ":" + expira,
                    String.valueOf(saque), Emoji.fromUnicode("💎")));
        }

        ctx.replyEmbedWithButtons(conteudo, eb.build(), botoes);
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
                        + "** (💎" + meu.kakera() + ") em troca de **" + dele.name()
                        + "** (💎" + dele.kakera() + ") de <@" + dele.ownerId() + ">~")
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
            if (id.startsWith("hclaim:")) {
                handleClaim(event, id);
            } else if (id.startsWith("hkak:")) {
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

    private void handleClaim(ButtonInteractionEvent event, String id) {
        if (!event.isFromGuild()) {
            return;
        }
        String[] partes = id.split(":");
        long charId = Long.parseLong(partes[1]);
        int kakera = Integer.parseInt(partes[2]);
        long expiraSeg = Long.parseLong(partes[3]);
        long agora = System.currentTimeMillis();
        String guildId = event.getGuild().getId();
        String userId = event.getUser().getId();

        if (agora / 1000L > expiraSeg) {
            responderEfemero(event, "Esse coração já parou de bater... rola de novo! (・_・;)");
            desabilitarBotao(event);
            return;
        }

        long proximoClaim = repo.getPlayer(guildId, userId).lastClaimMs() + INTERVALO_CLAIM.toMillis();
        if (agora < proximoClaim) {
            responderEfemero(event, "Calminha, coração apressado~ você pode casar de novo "
                    + relativo(proximoClaim) + "! (・∀・)");
            return;
        }

        List<MessageEmbed> embeds = event.getMessage().getEmbeds();
        if (embeds.isEmpty() || embeds.get(0).getTitle() == null) {
            return;
        }
        MessageEmbed original = embeds.get(0);
        String nome = original.getTitle();
        String serie = original.getDescription() == null
                ? "" : original.getDescription().split("\n")[0];
        String imagem = original.getImage() == null ? null : original.getImage().getUrl();

        HaremRepository.Claim claim = new HaremRepository.Claim(
                charId, nome, serie, imagem, kakera, userId, event.getUser().getEffectiveName());
        if (!repo.tryClaim(guildId, claim, agora)) {
            responderEfemero(event, "Tarde demais... **" + nome
                    + "** já casou com outra pessoa! (｡•́︿•̀｡)");
            HaremRepository.Claim dona = repo.findOwner(guildId, charId);
            if (dona != null) {
                event.getMessage().editMessageEmbeds(new EmbedBuilder(original)
                                .setColor(COR_CASADA)
                                .setFooter("💍 Pertence a " + dona.ownerName())
                                .build())
                        .setComponents(ActionRow.of(event.getButton().asDisabled()))
                        .queue();
            }
            return;
        }

        repo.setLastClaim(guildId, userId, agora);
        event.editMessageEmbeds(new EmbedBuilder(original)
                        .setColor(COR_RECEM_CASADA)
                        .setFooter("💍 Pertence a " + event.getUser().getEffectiveName())
                        .build())
                .setComponents(ActionRow.of(event.getButton().asDisabled()))
                .queue();
        event.getChannel().sendMessage("💖 **" + event.getUser().getEffectiveName() + "** e **"
                + nome + "** agora são casados! Que sejam felizes~ (´｡• ᵕ •｡`) ♡").queue();
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
        if (!kakeraColetado.add(event.getMessageIdLong())) {
            responderEfemero(event, "Alguém pegou esse kakera primeiro~ (>_<)");
            return;
        }
        if (kakeraColetado.size() > 5000) {
            kakeraColetado.clear();
        }

        String guildId = event.getGuild().getId();
        String userId = event.getUser().getId();
        // A torre de kakera aumenta o saque de quem clica.
        int nivel = repo.getPlayer(guildId, userId).towerLevel();
        int ganho = saque + saque * SAQUE_POR_NIVEL * nivel / 100;

        repo.addKakera(guildId, userId, ganho);
        event.editComponents(ActionRow.of(event.getButton().asDisabled())).queue();
        event.getChannel().sendMessage("💎 **" + event.getUser().getEffectiveName()
                + "** coletou **" + ganho + "** kakera!"
                + (nivel > 0 ? " (bônus da torre " + TORRE_EMOJIS[nivel] + ")" : "") + " ✧").queue();
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
            event.reply(texto).setEphemeral(true).queue();
        }
    }

    private void desabilitarBotao(ButtonInteractionEvent event) {
        event.getMessage()
                .editMessageComponents(ActionRow.of(event.getButton().asDisabled()))
                .queue(ok -> {}, err -> {});
    }

    /** Timestamp relativo do Discord ("em 23 minutos"). */
    public static String relativo(long epochMs) {
        return "<t:" + epochMs / 1000L + ":R>";
    }
}