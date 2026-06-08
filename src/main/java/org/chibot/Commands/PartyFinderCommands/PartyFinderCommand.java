package org.chibot.Commands.PartyFinderCommands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
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

    // Limites do Discord: ate 10 embeds por mensagem, 25 fields por embed e
    // 6000 caracteres somando TODOS os embeds. Deixamos margem de seguranca.
    private static final int MAX_EMBEDS = 10;
    private static final int MAX_FIELDS_PER_EMBED = 24; // 8 PF x 3 fields
    private static final int MAX_TOTAL_CHARS = 5500;

    // O bot so lista PF do Aether.
    private static final String DATA_CENTER = "Aether";

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

        return List.of(duty);
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.deferReply();

        String sel = normalizeSelection(firstNonNull(ctx.getOption("duty"), arg(ctx, 0), "all"));

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
            if (!DATA_CENTER.equalsIgnoreCase(l.dataCentre())) {
                continue; // so Aether
            }
            filtered.add(l);
        }

        ctx.replyEmbeds(buildEmbeds(filtered, sel));
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

    /**
     * Monta a resposta no estilo trappingway: um embed colorido por duty, e cada
     * PF como uma linha de 3 colunas (campos inline): autor+composicao, descricao
     * e tempo. Respeita os limites do Discord (10 embeds, 25 fields, 6000 chars).
     */
    private List<MessageEmbed> buildEmbeds(List<PfListing> listings, String sel) {
        if (listings.isEmpty()) {
            String titulo = "🗡️ Party Finder " + DATA_CENTER + " — " + selLabel(sel);
            return List.of(new EmbedBuilder()
                    .setColor(KAWAII_PINK)
                    .setTitle(titulo)
                    .setDescription("Nenhum PF abertinho no " + DATA_CENTER + " agora~ (´･ω･`) tenta outra duty ou volta mais tarde ♡")
                    .setFooter("via xivpf.com · cobertura parcial (plugin)~ ♡")
                    .build());
        }

        // agrupa por duty, em ordem alfabetica
        Map<String, List<PfListing>> porDuty = new LinkedHashMap<>();
        listings.stream()
                .sorted(Comparator.comparing(l -> l.duty() == null ? "" : l.duty()))
                .forEach(l -> porDuty.computeIfAbsent(l.duty(), k -> new ArrayList<>()).add(l));

        List<EmbedBuilder> builders = new ArrayList<>();
        int totalChars = 0;
        boolean first = true;
        boolean truncou = false;

        outer:
        for (Map.Entry<String, List<PfListing>> e : porDuty.entrySet()) {
            if (builders.size() >= MAX_EMBEDS) {
                truncou = true;
                break;
            }
            String duty = e.getKey();
            List<PfListing> grupo = e.getValue();
            grupo.sort(Comparator.comparingInt(PfListing::filled).reversed());

            String titulo = dutyEmoji(duty) + " " + safe(duty);
            EmbedBuilder embed = new EmbedBuilder().setColor(colorFor(duty)).setTitle(titulo);
            totalChars += titulo.length();
            if (first) {
                String legenda = "🛡️ Tank  ·  💚 Healer  ·  ⚔️ DPS  ·  ▫️ vaga aberta";
                embed.setDescription(legenda);
                totalChars += legenda.length();
                first = false;
            }

            int fields = 0;
            for (PfListing l : grupo) {
                if (fields >= MAX_FIELDS_PER_EMBED) {
                    truncou = true;
                    break;
                }
                String[] f = listingFields(l); // [nome1,val1, nome2,val2, nome3,val3]
                int custo = f[0].length() + f[1].length() + f[2].length()
                        + f[3].length() + f[4].length() + f[5].length();
                if (totalChars + custo > MAX_TOTAL_CHARS) {
                    truncou = true;
                    break outer;
                }
                embed.addField(f[0], f[1], true);
                embed.addField(f[2], f[3], true);
                embed.addField(f[4], f[5], true);
                totalChars += custo;
                fields += 3;
            }

            builders.add(embed);
        }

        // rodape so no ultimo embed, pra nao poluir
        EmbedBuilder last = builders.get(builders.size() - 1);
        last.setTimestamp(Instant.now());
        last.setFooter("xivpf.com · " + DATA_CENTER + " · " + listings.size() + " PF"
                + (truncou ? " (alguns omitidos)" : "") + " · cobertura parcial (plugin)~ ♡");

        List<MessageEmbed> out = new ArrayList<>();
        for (EmbedBuilder b : builders) {
            out.add(b.build());
        }
        return out;
    }

    /** As 3 colunas (campos inline) de um PF: [nome,val] x3. */
    private static String[] listingFields(PfListing l) {
        // col 1 — autor + composicao
        String autor = "👤 " + (l.creator() == null ? "?" : safe(trim(creatorName(l.creator()), 28)));
        String comp = compIcons(l.comp()) + "  `" + (l.slots() == null ? "?/?" : l.slots()) + "`";

        // col 2 — iLvl + descricao (o DC e sempre Aether, entao nem mostra)
        String meta = (l.minIL() != null && !l.minIL().isBlank() && !l.minIL().equals("0"))
                ? "iLvl " + l.minIL() : "​";
        String desc = (l.description() == null || l.description().isBlank())
                ? "​" : safe(trim(l.description(), 80));

        // col 3 — tempo
        String exp = "⌛ " + (l.expires() == null || l.expires().isBlank() ? "?" : shortTime(l.expires()));
        String upd = "🔄 " + (l.updated() == null || l.updated().isBlank() ? "?" : shortTime(l.updated()));

        return new String[]{autor, comp, meta, desc, exp, upd};
    }

    /** Composicao "THDDD---" em icones de role; vaga aberta vira ▫️. */
    private static String compIcons(String comp) {
        if (comp == null || comp.isBlank()) {
            return "▫️";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < comp.length(); i++) {
            b.append(switch (comp.charAt(i)) {
                case 'T' -> "🛡️";
                case 'H' -> "💚";
                case 'D' -> "⚔️";
                default -> "▫️";
            });
        }
        return b.toString();
    }

    /** Cor por duty, inspirada no trappingway. */
    private static Color colorFor(String duty) {
        if (duty == null) {
            return new Color(0xf0a057);
        }
        if (duty.contains("Unending Coil")) return new Color(0xfce100); // UCOB dourado
        if (duty.contains("Weapon")) return new Color(0x008bfc);        // UWU azul
        if (duty.contains("Epic of Alexander")) return new Color(0xfcaa00); // TEA laranja
        if (duty.contains("Dragonsong")) return new Color(0xf12916);    // DSR vermelho
        if (duty.contains("Omega Protocol")) return new Color(0x13aa9e); // TOP teal
        if (duty.contains("Futures Rewritten")) return new Color(0xa05cd6); // FRU roxo
        if (duty.contains("Dancing Mad")) return new Color(0xc850c0);   // UMAD magenta
        return new Color(0xf0a057); // savage / outros
    }

    private static String dutyEmoji(String duty) {
        return duty != null && duty.contains("(Ultimate)") ? "👑" : "⚔️";
    }

    private static String selLabel(String sel) {
        return switch (sel) {
            case "ult_all" -> "Ultimates";
            case "sav_all" -> "Savage";
            case "all" -> "Ultimates & Savage";
            default -> DUTY_LABEL.getOrDefault(sel, sel.toUpperCase(Locale.ROOT));
        };
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
