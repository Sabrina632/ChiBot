package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremBadges;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class ProfileCommand implements ICommand {

    private static final Set<String> COR = Set.of("cor", "color");
    private static final Set<String> BIO = Set.of("bio", "texto", "desc");
    private static final Set<String> FAV = Set.of("fav", "favorito", "favorita");
    private static final int BIO_MAX = 200;

    @Override
    public String getName() {
        return "profile";
    }

    @Override
    public List<String> getAliases() {
        return List.of("perfil");
    }

    @Override
    public String getDescription() {
        return "Seu perfil do harém: stats, rank, favorito, cor e bio personalizadas~ 🪞";
    }

    @Override
    public String getUsage() {
        return "profile [@usuario] | profile cor <hex> | profile bio <texto> | profile fav <personagem>";
    }

    @Override
    public String getCategory() {
        return "Harém";
    }

    @Override
    public boolean isGuildOnly() {
        return true;
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.USER, "usuario",
                        "De quem ver o perfil (vazio = o seu)", false),
                new OptionData(OptionType.STRING, "acao", "Personalizar o seu perfil", false)
                        .addChoice("cor", "cor")
                        .addChoice("bio", "bio")
                        .addChoice("favorito", "fav"),
                new OptionData(OptionType.STRING, "valor",
                        "Valor da personalização (hex da cor, texto da bio ou nome do personagem)", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String acao = ctx.getOption("acao");
        String valor = ctx.getOption("valor");
        String alvoId = ctx.getOption("usuario");

        // Prefixo: "profile cor #ff66aa", "profile bio oi~", "profile fav Rem", "profile @user".
        List<String> args = ctx.getArgs();
        if (acao == null && alvoId == null && !args.isEmpty()) {
            String primeiro = args.get(0).toLowerCase(Locale.ROOT);
            if (COR.contains(primeiro)) {
                acao = "cor";
            } else if (BIO.contains(primeiro)) {
                acao = "bio";
            } else if (FAV.contains(primeiro)) {
                acao = "fav";
            } else {
                alvoId = HaremUtils.resolveUserId(args.get(0));
                if (alvoId == null) {
                    ctx.reply("Não entendi~ usa `" + getUsage() + "` (・∀・)");
                    return;
                }
            }
            if (acao != null && args.size() > 1) {
                valor = String.join(" ", args.subList(1, args.size()));
            }
        }

        if (acao != null) {
            personalizar(ctx, service, acao, valor);
            return;
        }

        if (alvoId == null || alvoId.equals(ctx.getAuthor().getId())) {
            render(ctx, service, ctx.getAuthor().getId(),
                    ctx.getAuthor().getEffectiveName(), ctx.getAuthor().getEffectiveAvatarUrl());
            return;
        }

        String finalAlvoId = alvoId;
        ctx.deferReply();
        ctx.getJDA().retrieveUserById(alvoId).queue(
                user -> render(ctx, service, finalAlvoId, user.getEffectiveName(), user.getEffectiveAvatarUrl()),
                err -> ctx.reply("Não achei esse usuário em lugar nenhum~ (・_・;)"));
    }

    // ----------------------------------------------------------- visualizacao

    private void render(CommandContext ctx, HaremService service,
                        String donoId, String donoNome, String avatarUrl) {
        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();

        // Ver o próprio perfil já concede as conquistas pendentes.
        if (donoId.equals(ctx.getAuthor().getId())) {
            service.grantNewAchievements(guildId, donoId);
        }

        HaremRepository.Profile profile = repo.getProfile(guildId, donoId);
        HaremRepository.HaremStats stats = repo.haremStats(guildId, donoId);
        HaremRepository.Player player = repo.getPlayer(guildId, donoId);
        int rank = repo.haremRank(guildId, donoId);
        int desejos = repo.listWishes(guildId, donoId).size();

        // Avatar vai no autor (topo) em vez de thumbnail: assim os campos ocupam
        // a largura toda e um campo vazio depois de cada par força 2 colunas
        // alinhadas (com thumbnail o Discord alterna entre 2 e 3 por linha).
        String casamento = stats.primeiroClaimMs() > 0
                ? "<t:" + stats.primeiroClaimMs() / 1000L + ":D>"
                : "ainda não~";

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(profile.color() >= 0 ? new Color(profile.color()) : MusicUi.KAWAII_PINK)
                .setAuthor("ﾟ･✧ Perfil de " + donoNome + " ✧･ﾟ", null, avatarUrl)
                .setDescription(profile.bio() == null || profile.bio().isBlank()
                        ? "*Sem bio~ usa `profile bio <texto>` pra escrever uma!*"
                        : profile.bio())
                .addField(HaremEmojis.custom("prof_harem", "💍") + " Harém",
                        stats.count() + " personagem(ns) · " + HaremEmojis.kakera()
                                + " " + stats.valorTotal(), true)
                .addField(HaremEmojis.kakera() + " Kakera",
                        HaremEmojis.kakera() + " " + player.kakera(), true)
                .addBlankField(true)
                .addField(HaremEmojis.custom("prof_rank", "🏆") + " Rank",
                        rank > 0 ? "#" + rank + " do servidor" : "—", true)
                .addField(HaremEmojis.custom("prof_torre", "🏰") + " Torre",
                        HaremEmojis.torre(player.towerLevel())
                                + " nível " + player.towerLevel() + "/" + HaremService.TORRE_MAX, true)
                .addBlankField(true)
                .addField(HaremEmojis.custom("prof_wish", "✨") + " Desejos",
                        desejos + "/" + HaremService.MAX_DESEJOS, true)
                .addField(HaremEmojis.custom("prof_date", "📅") + " Primeiro casamento",
                        casamento, true)
                .addBlankField(true);

        List<String> badges = repo.equippedBadges(guildId, donoId);
        if (!badges.isEmpty()) {
            String fileira = badges.stream()
                    .map(HaremBadges::byId)
                    .filter(Objects::nonNull)
                    .map(HaremBadges.Badge::emoji)
                    .reduce((a, c) -> a + " " + c).orElse("");
            eb.addField(HaremEmojis.custom("prof_badges", "🎖️") + " Badges", fileira, false);
        }

        // Personagem favorito vira a imagem grande do perfil (se ainda for do jogador).
        HaremRepository.Claim fav = profile.favCharId() > 0
                ? repo.findOwner(guildId, profile.favCharId()) : null;
        if (fav != null && fav.ownerId().equals(donoId)) {
            eb.addField(HaremEmojis.custom("prof_fav", "💖") + " Favorito",
                            "**" + fav.name() + "** · " + fav.series(), false)
                    .setImage(fav.imageUrl());
        }

        ctx.replyEmbeds(eb.build());
    }

    // --------------------------------------------------------- personalizacao

    private void personalizar(CommandContext ctx, HaremService service, String acao, String valor) {
        if (valor == null || valor.isBlank()) {
            ctx.reply("Me fala o valor~ ex.: `profile cor #ff66aa`, `profile bio oi!`, `profile fav Rem` (・∀・)");
            return;
        }
        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        valor = valor.trim();

        switch (acao) {
            case "cor" -> {
                Integer cor = parseCor(valor);
                if (cor == null) {
                    ctx.reply("Cor inválida~ manda em hex, tipo `#ff66aa` (・_・;)");
                    return;
                }
                repo.setProfileColor(guildId, userId, cor);
                ctx.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(cor))
                        .setDescription("🎨 Cor do perfil atualizada pra `#"
                                + String.format("%06x", cor) + "`~ olha que linda! (✿◠‿◠)")
                        .build());
            }
            case "bio" -> {
                if (valor.length() > BIO_MAX) {
                    ctx.reply("Bio muito longa~ máximo de " + BIO_MAX + " caracteres! (>_<)");
                    return;
                }
                repo.setProfileBio(guildId, userId, valor);
                ctx.reply("📝 Bio atualizada~ ficou um charme! (´｡• ᵕ •｡`)");
            }
            case "fav" -> {
                HaremRepository.Claim alvo = HaremUtils.escolher(
                        repo.findClaims(guildId, userId, valor), valor);
                if (alvo == null) {
                    ctx.reply("Não achei **" + valor + "** no seu harém (ou achei mais de um)~ (・_・;)");
                    return;
                }
                repo.setProfileFav(guildId, userId, alvo.charId());
                ctx.reply("💖 **" + alvo.name() + "** agora é seu personagem favorito"
                        + " e aparece em destaque no seu perfil~ (✿◠‿◠)");
            }
            default -> ctx.reply("Não entendi~ usa `" + getUsage() + "` (・∀・)");
        }
    }

    /** Aceita {@code #ff66aa}, {@code ff66aa} ou {@code 0xff66aa}; null se invalido. */
    private Integer parseCor(String raw) {
        String hex = raw.toLowerCase(Locale.ROOT)
                .replaceFirst("^#", "")
                .replaceFirst("^0x", "");
        if (!hex.matches("[0-9a-f]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }
}