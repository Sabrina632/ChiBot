package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Config.ChiConfig;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.util.ArrayList;
import java.util.List;

public class HaremCommand implements ICommand {

    private static final int POR_PAGINA = 20;
    private static final int MAX_EMBEDS = 10;

    @Override
    public String getName() {
        return "harem";
    }

    @Override
    public List<String> getAliases() {
        return List.of("mm", "meuharem");
    }

    @Override
    public String getDescription() {
        return "Mostra seu harém (ou o de outra pessoa)~ ♡(˃͈ ˂͈ )";
    }

    @Override
    public String getUsage() {
        return "harem [@usuario] | harem <personagem> (define a imagem)";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.USER, "usuario",
                        "De quem ver o harém (vazio = o seu)", false),
                new OptionData(OptionType.STRING, "personagem",
                        "Personagem do seu harém pra usar como imagem", false));
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
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String alvoId = ctx.getOption("usuario");
        String personagem = ctx.getOption("personagem");

        // Prefixo: !harem @user (ver harém de alguém) vs !harem Rem (definir imagem).
        List<String> args = ctx.getArgs();
        if (alvoId == null && personagem == null && !args.isEmpty()) {
            String uid = HaremUtils.resolveUserId(args.get(0));
            if (uid != null) {
                alvoId = uid;
            } else {
                personagem = String.join(" ", args);
            }
        }

        if (personagem != null) {
            definirImagem(ctx, service, personagem);
            return;
        }

        if (alvoId == null || alvoId.equals(ctx.getAuthor().getId())) {
            render(ctx, service, ctx.getAuthor().getId(), ctx.getAuthor().getEffectiveName(), true);
            return;
        }

        // Harem de outra pessoa: resolve o nome de exibicao antes de montar.
        String finalAlvoId = alvoId;
        ctx.deferReply();
        ctx.getJDA().retrieveUserById(alvoId).queue(
                user -> render(ctx, service, finalAlvoId, user.getEffectiveName(), false),
                err -> ctx.reply("Não achei esse usuário em lugar nenhum~ (・_・;)"));
    }

    /** Define a personagem (do próprio harém) que vira a imagem do comando {@code harem}. */
    private void definirImagem(CommandContext ctx, HaremService service, String nome) {
        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        nome = nome.trim();

        HaremRepository.Claim alvo = HaremUtils.escolher(repo.findClaims(guildId, userId, nome), nome);
        if (alvo == null) {
            ctx.reply("Não achei **" + nome + "** no seu harém (ou achei mais de um)~ "
                    + "manda o nome certinho! (・_・;)");
            return;
        }
        repo.setHaremChar(guildId, userId, alvo.charId());
        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setDescription("🖼️ **" + alvo.name() + "** agora é a carinha do seu `harem`~ (✿◠‿◠)")
                .setThumbnail(alvo.imageUrl())
                .build());
    }

    private void render(CommandContext ctx, HaremService service,
                        String donoId, String donoNome, boolean proprio) {
        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        List<HaremRepository.Claim> harem = repo.listHarem(guildId, donoId);
        if (harem.isEmpty()) {
            String prefix = ChiConfig.get() == null ? "!" : ChiConfig.get().getPrefix();
            ctx.reply(proprio
                    ? "Seu harém tá vazio~ usa `" + prefix + "w` pra rolar uma waifu! (・∀・)"
                    : "O harém de **" + donoNome + "** tá vazio~ (・_・;)");
            return;
        }

        // Imagem: a personagem escolhida (se ainda for do dono), senão a mais valiosa.
        String thumb = harem.get(0).imageUrl();
        long escolhida = repo.getProfile(guildId, donoId).haremCharId();
        if (escolhida > 0) {
            for (HaremRepository.Claim c : harem) {
                if (c.charId() == escolhida) {
                    thumb = c.imageUrl();
                    break;
                }
            }
        }

        long valorTotal = harem.stream().mapToLong(HaremRepository.Claim::kakera).sum();
        List<MessageEmbed> paginas = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int exibidos = 0;
        for (HaremRepository.Claim claim : harem) {
            if (paginas.size() == MAX_EMBEDS) {
                break;
            }
            sb.append(HaremEmojis.kakera(claim.kakera())).append("`").append(claim.kakera())
                    .append("` **").append(claim.name())
                    .append("** · ").append(claim.series()).append('\n');
            exibidos++;
            if (exibidos % POR_PAGINA == 0 || exibidos == harem.size()) {
                paginas.add(pagina(donoNome, sb.toString(), paginas.isEmpty(),
                        harem.size(), valorTotal, thumb));
                sb.setLength(0);
            }
        }
        ctx.replyEmbeds(paginas);
    }

    private MessageEmbed pagina(String donoNome, String descricao, boolean primeira,
                                int total, long valorTotal, String thumbUrl) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setDescription(descricao);
        if (primeira) {
            eb.setTitle("ﾟ･✧ Harém de " + donoNome + " ✧･ﾟ")
                    .setThumbnail(thumbUrl);
        }
        eb.setFooter(total + " personagem(ns) · valor total: " + valorTotal + " kakera 💎");
        return eb.build();
    }
}