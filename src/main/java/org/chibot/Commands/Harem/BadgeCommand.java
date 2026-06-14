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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Badges colecionaveis do harem, estilo Mudae: uns desbloqueiam por conquista,
 * outros sao comprados com kakera na lojinha, e voce escolhe quais exibir no
 * {@code profile}.
 */
public class BadgeCommand implements ICommand {

    private static final Set<String> BUY = Set.of("buy", "comprar", "loja", "shop");
    private static final Set<String> EQUIP = Set.of("equip", "equipar", "usar", "use");
    private static final Set<String> UNEQUIP = Set.of("unequip", "desequipar", "tirar", "remove", "remover");

    @Override
    public String getName() {
        return "badge";
    }

    @Override
    public List<String> getAliases() {
        return List.of("badges", "bg", "emblema");
    }

    @Override
    public String getDescription() {
        return "Badges colecionáveis do harém: conquista, compra e exibe no perfil~ 🎖️";
    }

    @Override
    public String getUsage() {
        return "badge [@usuario] | badge buy <nome> | badge equip <nome> | badge tirar <nome>";
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
                new OptionData(OptionType.STRING, "acao", "O que fazer (vazio = ver sua coleção)", false)
                        .addChoice("comprar", "buy")
                        .addChoice("equipar", "equip")
                        .addChoice("tirar", "unequip"),
                new OptionData(OptionType.STRING, "nome", "Nome do badge (pra comprar/equipar/tirar)", false),
                new OptionData(OptionType.USER, "usuario", "Ver a coleção de outra pessoa", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String acao = ctx.getOption("acao");
        String nome = ctx.getOption("nome");
        String alvoId = ctx.getOption("usuario");

        // Prefixo: "badge buy sakura", "badge equip coracao", "badge @user", "badge".
        List<String> args = ctx.getArgs();
        if (acao == null && alvoId == null && !args.isEmpty()) {
            String primeiro = args.get(0).toLowerCase(Locale.ROOT);
            if (BUY.contains(primeiro)) {
                acao = "buy";
            } else if (EQUIP.contains(primeiro)) {
                acao = "equip";
            } else if (UNEQUIP.contains(primeiro)) {
                acao = "unequip";
            } else {
                alvoId = HaremUtils.resolveUserId(args.get(0));
                if (alvoId == null) {
                    ctx.reply("Não entendi~ usa `" + getUsage() + "` (・∀・)");
                    return;
                }
            }
            if (acao != null && args.size() > 1) {
                nome = String.join(" ", args.subList(1, args.size()));
            }
        }

        if (acao != null) {
            switch (acao) {
                case "buy" -> comprar(ctx, service, nome);
                case "equip" -> equipar(ctx, service, nome, true);
                case "unequip" -> equipar(ctx, service, nome, false);
                default -> ctx.reply("Não entendi~ usa `" + getUsage() + "` (・∀・)");
            }
            return;
        }

        if (alvoId == null || alvoId.equals(ctx.getAuthor().getId())) {
            galeria(ctx, service, ctx.getAuthor().getId(), ctx.getAuthor().getEffectiveName(), true);
            return;
        }

        String finalAlvoId = alvoId;
        ctx.deferReply();
        ctx.getJDA().retrieveUserById(alvoId).queue(
                user -> galeria(ctx, service, finalAlvoId, user.getEffectiveName(), false),
                err -> ctx.reply("Não achei esse usuário em lugar nenhum~ (・_・;)"));
    }

    // ----------------------------------------------------------- visualizacao

    private void galeria(CommandContext ctx, HaremService service,
                         String donoId, String donoNome, boolean ehAutor) {
        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();

        String aviso = null;
        if (ehAutor) {
            // Olhar a coleção já concede as conquistas pendentes.
            aviso = HaremService.anuncioBadges(donoNome, service.grantNewAchievements(guildId, donoId));
        }

        Set<String> owned = repo.ownedBadges(guildId, donoId);
        List<String> equipados = repo.equippedBadges(guildId, donoId);
        long kakera = repo.getPlayer(guildId, donoId).kakera();

        List<String> conquistas = new ArrayList<>();
        for (HaremBadges.Badge b : HaremBadges.conquistas()) {
            boolean tem = owned.contains(b.id());
            conquistas.add((tem ? "✅ " : "🔒 ") + b.emoji() + " **" + b.nome() + "** — " + b.descricao());
        }

        List<String> loja = new ArrayList<>();
        for (HaremBadges.Badge b : HaremBadges.loja()) {
            boolean tem = owned.contains(b.id());
            loja.add((tem ? "✅ " : "🛒 ") + b.emoji() + " **" + b.nome() + "** — "
                    + (tem ? "já é seu~" : HaremEmojis.kakera() + " " + b.preco()));
        }

        List<String> personagens = new ArrayList<>();
        for (HaremBadges.Badge b : HaremBadges.personagens()) {
            boolean tem = owned.contains(b.id());
            personagens.add((tem ? "✅ " : "🔒 ") + b.emoji() + " **" + b.nome() + "** · " + b.serie()
                    + " — " + (tem ? "conquistado~" : "case ou " + HaremEmojis.kakera() + " " + b.preco()));
        }

        String fileira = equipados.isEmpty()
                ? "*Nenhum~ usa `badge equip <nome>` pra exibir até " + HaremBadges.MAX_NO_PERFIL + " no perfil.*"
                : equipados.stream().map(HaremBadges::byId).filter(Objects::nonNull)
                        .map(HaremBadges.Badge::emoji).reduce((a, c) -> a + " " + c).orElse("");

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Badges de " + donoNome + " ✧･ﾟ 🎖️")
                .setDescription("Coleção: **" + owned.size() + "/" + HaremBadges.todos().size()
                        + "** badges")
                .addField("✨ No perfil (" + equipados.size() + "/" + HaremBadges.MAX_NO_PERFIL + ")",
                        fileira, false);
        addSecao(eb, "🏅 Conquistas", conquistas);
        addSecao(eb, "🎭 Personagens", personagens);
        addSecao(eb, "🛒 Lojinha", loja);
        if (ehAutor) {
            eb.setFooter("Você tem " + kakera + " kakera · badge buy/equip/tirar <nome>");
        }

        ctx.replyEmbedWithButtons(aviso, eb.build(), List.of());
    }

    /**
     * Adiciona uma secao como um ou mais campos do embed, quebrando antes de
     * estourar o limite de 1024 caracteres por campo (emoji custom ocupa bem
     * mais que o unicode, entao a quebra e por tamanho, nao por contagem fixa).
     */
    private void addSecao(EmbedBuilder eb, String titulo, List<String> linhas) {
        StringBuilder buf = new StringBuilder();
        boolean primeiro = true;
        for (String linha : linhas) {
            if (buf.length() + linha.length() + 1 > 1000) {
                eb.addField(primeiro ? titulo : titulo + " (cont.)", buf.toString(), false);
                buf.setLength(0);
                primeiro = false;
            }
            buf.append(linha).append('\n');
        }
        if (buf.length() > 0) {
            eb.addField(primeiro ? titulo : titulo + " (cont.)", buf.toString(), false);
        }
    }

    // ------------------------------------------------------------------ acoes

    private void comprar(CommandContext ctx, HaremService service, String nome) {
        if (nome == null || nome.isBlank()) {
            ctx.reply("Me fala qual badge comprar~ ex.: `badge buy sakura` (・∀・)");
            return;
        }
        HaremBadges.Badge b = HaremBadges.find(nome);
        if (b == null) {
            ctx.reply("Não achei o badge **" + nome + "**~ olha a lista com `badge` (・_・;)");
            return;
        }
        if (!b.compravel()) {
            ctx.reply(b.emoji() + " **" + b.nome() + "** não está à venda — é uma conquista! "
                    + "Desbloqueia jogando~ (✿◠‿◠)");
            return;
        }

        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();

        if (repo.ownedBadges(guildId, userId).contains(b.id())) {
            ctx.reply(b.emoji() + " você já tem o badge **" + b.nome() + "**~ (´｡• ᵕ •｡`)");
            return;
        }
        if (!repo.buyBadge(guildId, userId, b.id(), b.preco(), System.currentTimeMillis())) {
            long k = repo.getPlayer(guildId, userId).kakera();
            ctx.reply("Kakera insuficiente~ **" + b.nome() + "** custa " + HaremEmojis.kakera()
                    + " " + b.preco() + " e você tem " + HaremEmojis.kakera() + " " + k + ". (｡•́︿•̀｡)");
            return;
        }
        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setDescription("🛍️ Você comprou o badge " + b.emoji() + " **" + b.nome() + "** por "
                        + HaremEmojis.kakera() + " " + b.preco() + "!\n"
                        + "Usa `badge equip " + b.nome().toLowerCase(Locale.ROOT)
                        + "` pra exibir no seu perfil~ (✿◠‿◠)")
                .build());
    }

    private void equipar(CommandContext ctx, HaremService service, String nome, boolean equipar) {
        if (nome == null || nome.isBlank()) {
            ctx.reply("Me fala qual badge~ ex.: `badge " + (equipar ? "equip" : "tirar") + " sakura` (・∀・)");
            return;
        }
        HaremBadges.Badge b = HaremBadges.find(nome);
        if (b == null) {
            ctx.reply("Não achei o badge **" + nome + "**~ olha a lista com `badge` (・_・;)");
            return;
        }

        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();

        if (!repo.ownedBadges(guildId, userId).contains(b.id())) {
            ctx.reply("Você ainda não tem o badge " + b.emoji() + " **" + b.nome()
                    + "**~ conquista ou compra ele primeiro! (・_・;)");
            return;
        }

        boolean jaEquipado = repo.equippedBadges(guildId, userId).contains(b.id());
        if (equipar && jaEquipado) {
            ctx.reply(b.emoji() + " **" + b.nome() + "** já está no seu perfil~ (´｡• ᵕ •｡`)");
            return;
        }
        if (!equipar && !jaEquipado) {
            ctx.reply(b.emoji() + " **" + b.nome() + "** nem estava no seu perfil~ (・∀・)");
            return;
        }
        if (equipar && repo.equippedBadgeCount(guildId, userId) >= HaremBadges.MAX_NO_PERFIL) {
            ctx.reply("Seu perfil já tem " + HaremBadges.MAX_NO_PERFIL
                    + " badges~ tira um com `badge tirar <nome>` antes! (・_・;)");
            return;
        }

        repo.setBadgeEquipped(guildId, userId, b.id(), equipar);
        ctx.reply(equipar
                ? "✨ " + b.emoji() + " **" + b.nome() + "** agora aparece no seu perfil~ (✿◠‿◠)"
                : "🧹 " + b.emoji() + " **" + b.nome() + "** saiu do seu perfil~");
    }
}