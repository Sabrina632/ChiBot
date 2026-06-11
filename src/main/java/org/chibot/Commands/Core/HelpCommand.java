package org.chibot.Commands.Core;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.CommandManager;
import org.chibot.Commands.ICommand;
import org.chibot.Config.ChiConfig;
import org.chibot.Music.MusicUi;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class HelpCommand implements ICommand {

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
        return "help [comando]";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "comando",
                "Nome de um comando pra ver os detalhes dele", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        String target = ctx.getOption("comando");
        if ((target == null || target.isBlank()) && !ctx.getArgs().isEmpty()) {
            target = ctx.getArgs().get(0);
        }

        if (target != null && !target.isBlank()) {
            replyCommandDetail(ctx, target);
            return;
        }
        replyOverview(ctx);
    }

    /** Lista geral: um campo por categoria, com todos os comandos e descricoes. */
    private void replyOverview(CommandContext ctx) {
        String prefix = prefix();

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧♡ Comandos da Chi ♡✧･ﾟ")
                .setDescription("Oii~ eu sou a Chi! (✿◠‿◠) Olha tudo que eu sei fazer:\n"
                        + "pode usar `/comando` ou `" + prefix + "comando`, do jeitinho que preferir~ ♪")
                .setThumbnail(ctx.getJDA().getSelfUser().getEffectiveAvatarUrl())
                .setFooter("usa " + prefix + "help <comando> pra ver os detalhes~ ♡");

        // TreeMap pra ordem estavel das categorias (e dos comandos dentro delas).
        Map<String, Map<String, ICommand>> categories = new TreeMap<>();
        for (ICommand command : CommandManager.get().getCommands()) {
            categories.computeIfAbsent(command.getCategory(), k -> new TreeMap<>())
                    .put(command.getName(), command);
        }

        for (var category : categories.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (ICommand command : category.getValue().values()) {
                sb.append("`").append(command.getName()).append("` — ")
                        .append(command.getDescription()).append('\n');
            }
            embed.addField("˗ˏˋ ★ " + category.getKey() + " ★ ˎˊ˗", sb.toString(), false);
        }

        ctx.replyEmbeds(embed.build());
    }

    /** Detalhe de um comando so: como usar, atalhos e categoria. */
    private void replyCommandDetail(CommandContext ctx, String target) {
        String prefix = prefix();
        String name = target.toLowerCase().trim();
        if (name.startsWith(prefix)) {
            name = name.substring(prefix.length());
        } else if (name.startsWith("/")) {
            name = name.substring(1);
        }

        ICommand command = CommandManager.get().get(name);
        if (command == null) {
            ctx.reply("Hmm, não conheço esse comando~ (・_・;) dá uma olhadinha no `"
                    + prefix + "help`!");
            return;
        }

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

    private static String prefix() {
        ChiConfig config = ChiConfig.get();
        return config == null ? "!" : config.getPrefix();
    }
}