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
        OptionData tipo = new OptionData(OptionType.STRING, "tipo",
                "Ultimate, Savage ou ambos (padrao: ambos)", false)
                .addChoice("Ambos", "ambos")
                .addChoice("Ultimate", "ultimate")
                .addChoice("Savage", "savage");

        OptionData dc = new OptionData(OptionType.STRING, "datacenter",
                "Filtrar por Data Center (opcional)", false)
                .addChoice("Aether", "Aether").addChoice("Primal", "Primal")
                .addChoice("Crystal", "Crystal").addChoice("Dynamis", "Dynamis")
                .addChoice("Light", "Light").addChoice("Chaos", "Chaos")
                .addChoice("Materia", "Materia")
                .addChoice("Elemental", "Elemental").addChoice("Gaia", "Gaia")
                .addChoice("Mana", "Mana").addChoice("Meteor", "Meteor");

        return List.of(tipo, dc);
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.deferReply();

        String tipo = firstNonNull(ctx.getOption("tipo"), arg(ctx, 0), "ambos")
                .toLowerCase(Locale.ROOT);
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
            if (!matchesType(l, tipo)) {
                continue;
            }
            if (dc != null && !dc.equalsIgnoreCase(l.dataCentre())) {
                continue;
            }
            filtered.add(l);
        }

        ctx.replyEmbeds(buildEmbed(filtered, tipo, dc));
    }

    private static boolean matchesType(PfListing l, String tipo) {
        return switch (tipo) {
            case "ultimate" -> l.isUltimate();
            case "savage" -> l.isSavage();
            default -> l.isUltimate() || l.isSavage(); // "ambos"
        };
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildEmbed(
            List<PfListing> listings, String tipo, String dc) {

        String tipoLabel = switch (tipo) {
            case "ultimate" -> "Ultimates";
            case "savage" -> "Savage";
            default -> "Ultimates & Savage";
        };
        String titulo = "🗡️ Party Finder — " + tipoLabel + (dc != null ? " · " + dc : "");

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
        int mostradas = 0;
        boolean truncou = false;

        outer:
        for (Map.Entry<String, List<PfListing>> e : porDuty.entrySet()) {
            String header = "\n**" + safe(e.getKey()) + "**  ·  " + e.getValue().size() + "\n";
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
                mostradas++;
            }
        }

        if (truncou) {
            sb.append("\n*…e mais. Use o filtro `datacenter` pra afunilar~*");
        }

        embed.setDescription(sb.toString());
        embed.setFooter("via xivpf.com · " + listings.size() + " PF · cobertura parcial (plugin)~ ♡");
        return embed.build();
    }

    private static String formatLinha(PfListing l) {
        StringBuilder b = new StringBuilder();
        b.append("`").append(l.slots() == null ? "?/?" : l.slots()).append("`");
        if (l.minIL() != null && !l.minIL().isBlank() && !l.minIL().equals("0")) {
            b.append(" · iLvl ").append(l.minIL());
        }
        b.append(" · 🌐 ").append(safe(l.dataCentre()));
        if (l.expires() != null && !l.expires().isBlank()) {
            b.append(" · ⏳ ").append(safe(l.expires()));
        }
        if (l.creator() != null && !l.creator().isBlank()) {
            b.append(" — ").append(safe(trim(l.creator(), 32)));
        }
        b.append("\n");
        return b.toString();
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
