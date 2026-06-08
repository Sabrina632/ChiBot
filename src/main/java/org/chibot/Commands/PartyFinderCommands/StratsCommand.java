package org.chibot.Commands.PartyFinderCommands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.PfRepository;

import java.awt.Color;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /strats} (ou {@code !strats <duty>}) — mostra as strats mais usadas nas
 * descricoes dos Party Finder de uma duty, descobertas automaticamente.
 *
 * <p>O {@link PartyFinderService} tokeniza as descricoes dos PF do Aether e
 * acumula a contagem de cada termo por duty ao longo do tempo (cada PF conta
 * uma vez). Este comando so le esse ranking — quanto mais o bot roda, mais
 * dados. Inspirado no projeto xivpf-tokenizer.
 */
public class StratsCommand implements ICommand {

    private static final Color KAWAII_PINK = new Color(0xFFB6C1);
    private static final int TOP_N = 10;
    private static final int BAR_LEN = 12;

    private final StratsService service = new StratsService();

    // Sigla -> trecho do nome da duty pra casar; rotulo amigavel; e cor de accent.
    private static final Map<String, String> DUTY_MATCH = new LinkedHashMap<>();
    private static final Map<String, String> DUTY_LABEL = new LinkedHashMap<>();
    private static final Map<String, Color> DUTY_COLOR = new LinkedHashMap<>();
    static {
        duty("ucob", "Unending Coil",    "UCOB", 0xfce100);
        duty("uwu",  "Weapon",           "UWU",  0x008bfc);
        duty("tea",  "Epic of Alexander","TEA",  0xfcaa00);
        duty("dsr",  "Dragonsong",       "DSR",  0xf12916);
        duty("top",  "Omega Protocol",   "TOP",  0x13aa9e);
        duty("fru",  "Futures Rewritten","FRU",  0xa05cd6);
        duty("umad", "Dancing Mad",      "UMAD", 0xc850c0);
    }

    private static void duty(String sigla, String match, String label, int rgb) {
        DUTY_MATCH.put(sigla, match);
        DUTY_LABEL.put(sigla, label);
        DUTY_COLOR.put(sigla, new Color(rgb));
    }

    @Override
    public String getName() {
        return "strats";
    }

    @Override
    public List<String> getAliases() {
        return List.of("strat", "strategies");
    }

    @Override
    public String getDescription() {
        return "Mostra as strats mais usadas nos PF de uma duty (ex.: strats fru)~ ♡";
    }

    @Override
    public String getUsage() {
        return "strats <duty> (ex.: strats fru)";
    }

    @Override
    public String getCategory() {
        return "FFXIV";
    }

    @Override
    public List<OptionData> getOptions() {
        OptionData duty = new OptionData(OptionType.STRING, "duty",
                "De qual conteudo ver as strats", true)
                .addChoice("UCOB — Unending Coil of Bahamut", "ucob")
                .addChoice("UWU — The Weapon's Refrain", "uwu")
                .addChoice("TEA — The Epic of Alexander", "tea")
                .addChoice("DSR — Dragonsong's Reprise", "dsr")
                .addChoice("TOP — The Omega Protocol", "top")
                .addChoice("FRU — Futures Rewritten", "fru")
                .addChoice("UMAD — Dancing Mad", "umad");

        return List.of(duty);
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.deferReply();

        String sel = normalizeSelection(firstNonNull(ctx.getOption("duty"), arg(ctx, 0), ""));
        String sub = DUTY_MATCH.get(sel);
        if (sub == null) {
            ctx.reply("Diz qual duty~ (´･ω･`) ex.: `!strats fru` · opcoes: "
                    + String.join(", ", DUTY_LABEL.values()));
            return;
        }

        // Busca mais que o necessario e descarta ruido (ex.: "one player per job"),
        // inclusive o que ja foi acumulado, antes de cortar no TOP_N.
        List<PfRepository.TokenCount> tokens = service.topStrats(sub, TOP_N * 5).stream()
                .filter(t -> !StratsTokenizer.isNoise(t.token()))
                .limit(TOP_N)
                .toList();
        ctx.replyEmbeds(buildEmbed(sel, tokens));
    }

    private MessageEmbed buildEmbed(String sel, List<PfRepository.TokenCount> tokens) {
        String label = DUTY_LABEL.getOrDefault(sel, sel.toUpperCase(Locale.ROOT));
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(DUTY_COLOR.getOrDefault(sel, KAWAII_PINK))
                .setAuthor("Party Finder · Aether")
                .setTitle("✨ Strats mais usadas em " + label + " ✨");

        if (tokens.isEmpty()) {
            embed.setDescription("Ainda nao juntei dados de strat pra **" + label
                    + "**~ (´･ω･`)\nO bot vai aprendendo conforme novos PF vao aparecendo — "
                    + "tenta de novo daqui a pouco ♡");
            return embed.build();
        }

        int max = tokens.get(0).count();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            PfRepository.TokenCount t = tokens.get(i);
            sb.append(rank(i + 1)).append(" **").append(t.token()).append("**")
                    .append("  `").append(t.count()).append("`\n")
                    .append(bar(t.count(), max)).append('\n');
        }
        embed.setDescription(sb.toString().stripTrailing());
        embed.setTimestamp(Instant.now());
        embed.setFooter("♡ acumulado das descricoes dos PF · quanto mais o bot roda, melhor");
        return embed.build();
    }

    /** Medalha pro top 3, numero pro resto. */
    private static String rank(int pos) {
        return switch (pos) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> "`#" + pos + "`";
        };
    }

    /** Barra proporcional ao mais frequente (■ cheio, □ vazio). */
    private static String bar(int count, int max) {
        int filled = max <= 0 ? 0 : Math.max(1, Math.round((float) count / max * BAR_LEN));
        return "`" + "█".repeat(filled) + "░".repeat(Math.max(0, BAR_LEN - filled)) + "`";
    }

    /** Aceita os valores do slash e tambem sinonimos digitados no prefixo. */
    private static String normalizeSelection(String s) {
        String v = s.toLowerCase(Locale.ROOT).trim();
        return switch (v) {
            case "dmu", "dm", "dancing mad" -> "umad";
            default -> v; // siglas (fru, ucob, ...) caem direto no DUTY_MATCH
        };
    }

    private static String arg(CommandContext ctx, int i) {
        List<String> args = ctx.getArgs();
        return (args != null && args.size() > i) ? args.get(i) : null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}