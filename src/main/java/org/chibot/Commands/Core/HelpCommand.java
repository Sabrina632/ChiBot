package org.chibot.Commands.Core;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.CommandManager;
import org.chibot.Commands.ICommand;
import org.chibot.Config.ChiConfig;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Music.MusicUi;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ajuda navegável: o overview mostra só as categorias (ícone + blurb) com um botão
 * cada; clicar troca o mesmo embed pra lista de comandos daquela categoria. O
 * detalhe de um comando ({@code help <comando>}) continua igual. Os botões são
 * tratados pelo {@link HelpButtons}; os métodos de construção aqui são estáticos
 * justamente pra esse listener reusar.
 */
public class HelpCommand implements ICommand {

    /** Ordem + ícone (emoji.gg) + blurb de cada categoria. Categorias fora daqui vão pro fim. */
    private record Meta(String categoria, String emojiNome, String fallback, String blurb) {
    }

    private static final List<Meta> METAS = List.of(
            new Meta("Música", "cat_musica", "🎵", "tocar música no canal de voz"),
            new Meta("Harém", "cat_harem", "💞", "colecione personagens (estilo Mudae)"),
            new Meta("Diversão", "cat_diversao", "🎀", "hug, kiss, pat, slap..."),
            new Meta("Moderação", "cat_moderacao", "🛡️", "limpar mensagens, ban, kick, mute"),
            new Meta("Utilidades", "cat_utils", "⚙️", "ajuda, idioma, ping"),
            new Meta("Dono", "cat_dono", "👑", "comandos só do dono da Chi"),
            new Meta("FFXIV", "cat_ffxiv", "⚔️", "party finder e estratégias"));

    private static final Meta META_PADRAO = new Meta("", "", "★", "");

    /** Uma categoria com seus comandos já ordenados por nome. */
    public record CategoryGroup(String categoria, List<ICommand> comandos) {
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public List<String> getAliases() {
        return List.of("ajuda", "comandos");
    }

    @Override
    public String getDescription() {
        return "Mostra tudo que eu sei fazer~ (✿◠‿◠) ♡";
    }

    @Override
    public String getUsage() {
        return "help [comando | categoria]";
    }

    @Override
    public String getCategory() {
        return "Utilidades";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "alvo",
                "Nome de um comando ou categoria pra ver os detalhes", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        String alvo = ctx.getOption("alvo");
        if ((alvo == null || alvo.isBlank()) && !ctx.getArgs().isEmpty()) {
            alvo = ctx.getArgs().get(0);
        }

        if (alvo == null || alvo.isBlank()) {
            replyOverview(ctx);
            return;
        }

        ICommand command = CommandManager.get().get(stripPrefix(alvo));
        if (command != null) {
            replyCommandDetail(ctx, command);
            return;
        }

        String categoria = matchCategoria(alvo);
        if (categoria != null) {
            MessageEmbed embed = buildCategoryEmbed(categoria, prefix());
            ctx.replyEmbedWithButtons(null, embed, List.of(buildBackButton(ctx.getAuthor().getId())));
            return;
        }

        ctx.reply("Hmm, não conheço esse comando nem categoria~ (・_・;) dá uma olhadinha no `"
                + prefix() + "help`!");
    }

    // ------------------------------------------------------------------- overview

    private void replyOverview(CommandContext ctx) {
        MessageEmbed embed = buildOverviewEmbed(ctx.getJDA(), prefix());
        ctx.replyEmbedWithButtons(null, embed, buildOverviewButtons(ctx.getAuthor().getId()));
    }

    /** Embed do overview: uma linha por categoria (sem listar comandos). */
    static MessageEmbed buildOverviewEmbed(JDA jda, String prefix) {
        StringBuilder desc = new StringBuilder()
                .append("Oii~ eu sou a Chi! (✿◠‿◠) Escolhe uma categoria aqui embaixo pra ver os comandos:\n")
                .append("dá pra usar `/` ou `").append(prefix).append("` antes do comando, como preferir~ ♪\n\n");
        for (CategoryGroup g : agruparPorCategoria(CommandManager.get().getCommands())) {
            Meta m = metaFor(g.categoria());
            desc.append(HaremEmojis.custom(m.emojiNome(), m.fallback()))
                    .append(" **").append(g.categoria()).append("**");
            if (!m.blurb().isBlank()) {
                desc.append(" · ").append(m.blurb());
            }
            desc.append('\n');
        }
        return new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧♡ Comandos da Chi ♡✧･ﾟ")
                .setThumbnail(jda.getSelfUser().getEffectiveAvatarUrl())
                .setDescription(desc.toString())
                .setFooter("clica numa categoria · " + prefix + "help <comando> mostra os detalhes~ ♡")
                .build();
    }

    /** Um botão por categoria (ícone custom + nome), pro overview. */
    static List<Button> buildOverviewButtons(String invocadorId) {
        List<Button> botoes = new ArrayList<>();
        for (CategoryGroup g : agruparPorCategoria(CommandManager.get().getCommands())) {
            Meta m = metaFor(g.categoria());
            botoes.add(Button.secondary(HelpButtonId.cat(invocadorId, g.categoria()), g.categoria())
                    .withEmoji(HaremEmojis.customEmoji(m.emojiNome(), m.fallback())));
        }
        return botoes;
    }

    // ------------------------------------------------------------------ categoria

    /** Embed de uma categoria: lista os comandos com descrição. {@code null} se não existir. */
    static MessageEmbed buildCategoryEmbed(String categoria, String prefix) {
        CategoryGroup grupo = agruparPorCategoria(CommandManager.get().getCommands()).stream()
                .filter(g -> g.categoria().equalsIgnoreCase(categoria))
                .findFirst().orElse(null);
        if (grupo == null) {
            return null;
        }
        Meta m = metaFor(grupo.categoria());
        StringBuilder desc = new StringBuilder();
        if (!m.blurb().isBlank()) {
            desc.append(HaremEmojis.custom(m.emojiNome(), m.fallback()))
                    .append(' ').append(m.blurb()).append("\n\n");
        }
        for (ICommand c : grupo.comandos()) {
            desc.append('`').append(c.getName()).append("` — ").append(c.getDescription()).append('\n');
        }
        return new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ " + grupo.categoria() + " ✧･ﾟ")
                .setDescription(desc.toString())
                .setFooter("usa " + prefix + "help <comando> pra ver os detalhes~ ♡")
                .build();
    }

    /** Botão ⬅️ Voltar (pro overview). */
    static Button buildBackButton(String invocadorId) {
        return Button.secondary(HelpButtonId.home(invocadorId), "Voltar")
                .withEmoji(Emoji.fromUnicode("⬅️"));
    }

    // -------------------------------------------------------------- detalhe (igual)

    private void replyCommandDetail(CommandContext ctx, ICommand command) {
        String prefix = prefix();
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ " + command.getName() + " ✧･ﾟ")
                .setDescription(command.getDescription())
                .addField("♡ Como usar", "`" + prefix + command.getUsage() + "`", false)
                .setFooter("categoria: " + command.getCategory() + " ♡");
        if (!command.getAliases().isEmpty()) {
            embed.addField("☆ Atalhos", "`" + String.join("`, `", command.getAliases()) + "`", false);
        }
        ctx.replyEmbeds(embed.build());
    }

    // ----------------------------------------------------------------- agrupamento

    /**
     * Agrupa os comandos por categoria na ordem fixa do {@link #METAS} (categorias
     * fora dele entram no fim, em ordem alfabética); dentro de cada categoria os
     * comandos saem ordenados por nome. Puro — não toca em JDA.
     */
    static List<CategoryGroup> agruparPorCategoria(Collection<ICommand> comandos) {
        Map<String, List<ICommand>> porCategoria = new HashMap<>();
        for (ICommand c : comandos) {
            porCategoria.computeIfAbsent(c.getCategory(), k -> new ArrayList<>()).add(c);
        }
        List<CategoryGroup> out = new ArrayList<>();
        for (Meta m : METAS) {
            List<ICommand> cs = porCategoria.remove(m.categoria());
            if (cs != null && !cs.isEmpty()) {
                out.add(grupo(m.categoria(), cs));
            }
        }
        porCategoria.keySet().stream().sorted()
                .forEach(cat -> out.add(grupo(cat, porCategoria.get(cat))));
        return out;
    }

    private static CategoryGroup grupo(String categoria, List<ICommand> comandos) {
        List<ICommand> ordenados = new ArrayList<>(comandos);
        ordenados.sort(Comparator.comparing(ICommand::getName));
        return new CategoryGroup(categoria, ordenados);
    }

    private static Meta metaFor(String categoria) {
        return METAS.stream().filter(m -> m.categoria().equals(categoria))
                .findFirst().orElse(META_PADRAO);
    }

    // ------------------------------------------------------------------ auxiliares

    private String matchCategoria(String alvo) {
        String n = normalizar(alvo);
        for (CategoryGroup g : agruparPorCategoria(CommandManager.get().getCommands())) {
            if (normalizar(g.categoria()).equals(n)) {
                return g.categoria();
            }
        }
        return null;
    }

    /** Tira acentos/espaços/maiúsculas pra casar "musica" com "Música", "partyfinder" etc. */
    private static String normalizar(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]", "");
    }

    private String stripPrefix(String alvo) {
        String name = alvo.toLowerCase().trim();
        String prefix = prefix();
        if (name.startsWith(prefix)) {
            return name.substring(prefix.length());
        }
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        return name;
    }

    static String prefix() {
        ChiConfig config = ChiConfig.get();
        return config == null ? "!" : config.getPrefix();
    }
}