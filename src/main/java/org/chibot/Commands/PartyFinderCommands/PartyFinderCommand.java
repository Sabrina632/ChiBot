package org.chibot.Commands.PartyFinderCommands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /pf} (ou {@code !pf}) — lista os Party Finder de Ultimates e Savage,
 * agrupados por duty. Dados via {@link PartyFinderService} (xivpf.com).
 */
public class PartyFinderCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(PartyFinderCommand.class);
    private static final Color KAWAII_PINK = new Color(0xFFB6C1);

    // Limite de seguranca pra descricao do embed (o maximo do Discord e 4096).
    private static final int MAX_DESC = 3800;

    private final PartyFinderService service = new PartyFinderService();

    // Sigla -> trecho do nome da duty pra casar (sem apostrofo, pra evitar encoding).
    private static final Map<String, String> DUTY_MATCH = new LinkedHashMap<>();
    // Sigla -> rotulo amigavel pro titulo do embed.
    private static final Map<String, String> DUTY_LABEL = new LinkedHashMap<>();
    static {
        DUTY_MATCH.put("ucob", "Unending Coil");   DUTY_LABEL.put("ucob", "UCOB");
        DUTY_MATCH.put("uwu", "Weapon");            DUTY_LABEL.put("uwu", "UWU");
        DUTY_MATCH.put("tea", "Epic of Alexander"); DUTY_LABEL.put("tea", "TEA");
        DUTY_MATCH.put("dsr", "Dragonsong");        DUTY_LABEL.put("dsr", "DSR");
        DUTY_MATCH.put("top", "Omega Protocol");    DUTY_LABEL.put("top", "TOP");
        DUTY_MATCH.put("fru", "Futures Rewritten"); DUTY_LABEL.put("fru", "FRU");
        DUTY_MATCH.put("umad", "Dancing Mad");      DUTY_LABEL.put("umad", "UMAD");
    }

    @Override
    public String getName() {
        return "pf";
    }

    @Override
    public List<String> getAliases() {
        return List.of("partyfinder");
    }

    @Override
    public String getDescription() {
        return "Lista os Party Finder de Ultimates e Savage (via xivpf.com)~ ♡";
    }

    @Override
    public String getCategory() {
        return "FFXIV";
    }

    @Override
    public List<OptionData> getOptions() {
        OptionData duty = new OptionData(OptionType.STRING, "duty",
                "Qual conteudo mostrar (padrao: todos Ult + Savage)", false)
                .addChoice("Todos (Ult + Savage)", "all")
                .addChoice("Todos Ultimates", "ult_all")
                .addChoice("Todos Savage", "sav_all")
                .addChoice("UCOB — Unending Coil of Bahamut", "ucob")
                .addChoice("UWU — The Weapon's Refrain", "uwu")
                .addChoice("TEA — The Epic of Alexander", "tea")
                .addChoice("DSR — Dragonsong's Reprise", "dsr")
                .addChoice("TOP — The Omega Protocol", "top")
                .addChoice("FRU — Futures Rewritten", "fru")
                .addChoice("UMAD — Dancing Mad", "umad");

        OptionData dc = new OptionData(OptionType.STRING, "datacenter",
                "Filtrar por Data Center (opcional)", false)
                .addChoice("Aether", "Aether").addChoice("Primal", "Primal")
                .addChoice("Crystal", "Crystal").addChoice("Dynamis", "Dynamis")
                .addChoice("Light", "Light").addChoice("Chaos", "Chaos")
                .addChoice("Materia", "Materia")
                .addChoice("Elemental", "Elemental").addChoice("Gaia", "Gaia")
                .addChoice("Mana", "Mana").addChoice("Meteor", "Meteor");

        return List.of(duty, dc);
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.deferReply();

        String sel = normalizeSelection(firstNonNull(ctx.getOption("duty"), arg(ctx, 0), "all"));
        String dc = firstNonNull(ctx.getOption("datacenter"), arg(ctx, 1), null);

        List<PfListing> all;
        try {
            all = service.getListings();
        } catch (IOException e) {
            log.error("Falha ao buscar listagens do xivpf.com", e);
            ctx.reply("Nao consegui falar com o xivpf.com agora~ (；△；) tenta de novo daqui a pouco.");
            return;
        }

        List<PfListing> filtered = new ArrayList<>();
        for (PfListing l : all) {
            if (!matchesSelection(l, sel)) {
                continue;
            }
            if (dc != null && !dc.equalsIgnoreCase(l.dataCentre())) {
                continue;
            }
            filtered.add(l);
        }

        ctx.replyEmbeds(buildEmbed(filtered, sel, dc));
    }

    /** Aceita os valores do slash e tambem sinonimos digitados no prefixo. */
    private static String normalizeSelection(String s) {
        String v = s.toLowerCase(Locale.ROOT).trim();
        return switch (v) {
            case "", "all", "ambos", "todos" -> "all";
            case "ult", "ultimate", "ultimates", "ult_all" -> "ult_all";
            case "sav", "savage", "savages", "sav_all" -> "sav_all";
            default -> v; // siglas (ucob, uwu, ...) caem direto no DUTY_MATCH
        };
    }

    private static boolean matchesSelection(PfListing l, String sel) {
        switch (sel) {
            case "all":
                return l.isUltimate() || l.isSavage();
            case "ult_all":
                return l.isUltimate();
            case "sav_all":
                return l.isSavage();
            default:
                String sub = DUTY_MATCH.get(sel);
                return sub != null && l.duty() != null && l.duty().contains(sub);
        }
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildEmbed(
            List<PfListing> listings, String sel, String dc) {

        String selLabel = switch (sel) {
            case "ult_all" -> "Ultimates";
            case "sav_all" -> "Savage";
            case "all" -> "Ultimates & Savage";
            default -> DUTY_LABEL.getOrDefault(sel, sel.toUpperCase(Locale.ROOT));
        };
        String titulo = "🗡️ Party Finder — " + selLabel + (dc != null ? " · " + dc : "");

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(KAWAII_PINK)
                .setTitle(titulo)
                .setTimestamp(Instant.now())
                .setFooter("via xivpf.com · cobertura parcial (so quem usa o plugin)~ ♡");

        if (listings.isEmpty()) {
            embed.setDescription("Nenhum PF abertinho agora~ (´･ω･`) tenta outro tipo/DC ou volta mais tarde ♡");
            return embed.build();
        }

        // agrupa por duty, mantendo ordem alfabetica
        Map<String, List<PfListing>> porDuty = new LinkedHashMap<>();
        listings.stream()
                .sorted(Comparator.comparing(l -> l.duty() == null ? "" : l.duty()))
                .forEach(l -> porDuty.computeIfAbsent(l.duty(), k -> new ArrayList<>()).add(l));

        StringBuilder sb = new StringBuilder();
        sb.append("🟦 Tank  🟩 Healer  🟥 DPS  ⬜ Vaga\n");
        boolean truncou = false;

        outer:
        for (Map.Entry<String, List<PfListing>> e : porDuty.entrySet()) {
            String header = "\n**" + safe(e.getKey()) + "**  ·  " + e.getValue().size() + " PF\n";
            if (sb.length() + header.length() > MAX_DESC) {
                truncou = true;
                break;
            }
            sb.append(header);

            // dentro da duty, parties mais cheias primeiro
            e.getValue().sort(Comparator.comparingInt(PfListing::filled).reversed());
            for (PfListing l : e.getValue()) {
                String linha = formatLinha(l);
                if (sb.length() + linha.length() > MAX_DESC) {
                    truncou = true;
                    break outer;
                }
                sb.append(linha);
            }
        }

        if (truncou) {
            sb.append("\n*…e tem mais~ use `duty:` ou `datacenter:` pra afunilar ♡*");
        }

        embed.setDescription(sb.toString());
        embed.setFooter("via xivpf.com · " + listings.size() + " PF · cobertura parcial (plugin)~ ♡");
        return embed.build();
    }

    private static String formatLinha(PfListing l) {
        StringBuilder b = new StringBuilder();
        b.append(compBar(l.comp()));
        b.append(" `").append(l.slots() == null ? "?/?" : l.slots()).append("`");
        b.append(" · ").append(safe(l.dataCentre()));
        if (l.expires() != null && !l.expires().isBlank()) {
            b.append(" · ⏳ ").append(shortTime(l.expires()));
        }
        if (l.minIL() != null && !l.minIL().isBlank() && !l.minIL().equals("0")) {
            b.append(" · iL").append(l.minIL());
        }
        if (l.creator() != null && !l.creator().isBlank()) {
            b.append(" · 👤 ").append(safe(trim(creatorName(l.creator()), 22)));
        }
        b.append("\n");
        return b.toString();
    }

    /** Converte a composicao "THDDD---" em quadradinhos coloridos por role. */
    private static String compBar(String comp) {
        if (comp == null || comp.isBlank()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < comp.length(); i++) {
            b.append(switch (comp.charAt(i)) {
                case 'T' -> "🟦";
                case 'H' -> "🟩";
                case 'D' -> "🟥";
                default -> "⬜";
            });
        }
        return b.toString();
    }

    /** "in 30 minutes" -> "30m", "in 1 hour" -> "1h", "now" -> "agora". */
    private static String shortTime(String s) {
        String v = s.toLowerCase(Locale.ROOT).trim();
        if (v.equals("now")) {
            return "agora";
        }
        v = v.replace("in ", "")
                .replace("an hour", "1 hour")
                .replace("a minute", "1 minute")
                .replaceAll("\\s*hours?", "h")
                .replaceAll("\\s*minutes?", "m")
                .replaceAll("\\s*seconds?", "s");
        return safe(v);
    }

    /** Mantem so o nome do personagem (tira o "@ Mundo"). */
    private static String creatorName(String creator) {
        int at = creator.indexOf(" @ ");
        return at > 0 ? creator.substring(0, at) : creator;
    }

    /** Escapa markdown que quebraria o embed e tira quebras de linha. */
    private static String safe(String s) {
        if (s == null) {
            return "?";
        }
        return s.replace("*", "").replace("_", "").replace("`", "").replace("\n", " ").trim();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
