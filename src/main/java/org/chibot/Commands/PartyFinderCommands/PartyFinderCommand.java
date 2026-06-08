package org.chibot.Commands.PartyFinderCommands;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /pf} (ou {@code !pf}) — lista os Party Finder de Ultimates e Savage,
 * agrupados por duty, em texto puro (estilo trappingway). Dados via
 * {@link PartyFinderService} (xivpf.com).
 */
public class PartyFinderCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(PartyFinderCommand.class);

    // Mensagem de texto do Discord aceita ate 2000 chars; quebramos em varias
    // com margem, e limitamos a quantidade pra nao virar spam.
    private static final int MAX_MSG_CHARS = 1900;
    private static final int MAX_MESSAGES = 6;

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

    // Codigo do job -> emoji custom do bot (application emoji). Inclui as classes
    // base (GLA, MRD, ...) apontando pro mesmo icone do job, por seguranca.
    private static final Map<String, String> JOB_EMOJI = new java.util.HashMap<>();
    static {
        // Tanks
        job("PLD", "Paladin", "1513607339939336364", "GLA");
        job("WAR", "Warrior", "1513607338156757233", "MRD");
        job("DRK", "DarkKnight", "1513607336650870986");
        job("GNB", "Gunbreaker", "1513607335510278307");
        // Healers
        job("WHM", "WhiteMage", "1513607438245564586", "CNJ");
        job("SCH", "Scholar", "1513607436966170785");
        job("AST", "Astrologian", "1513607435682713740");
        job("SGE", "Sage", "1513607433862512670");
        // Melee
        job("MNK", "Monk", "1513607522060337264", "PGL");
        job("DRG", "Dragoon", "1513607520755777567", "LNC");
        job("NIN", "Ninja", "1513607515185610914", "ROG");
        job("SAM", "Samurai", "1513607512514101310");
        job("RPR", "Reaper", "1513607505958142233");
        job("VPR", "Viper", "1513607507359170651");
        // Physical Ranged
        job("BRD", "Bard", "1513607519375851672", "ARC");
        job("MCH", "Machinist", "1513607513843437712");
        job("DNC", "Dancer", "1513607508655345715");
        // Magical Ranged
        job("BLM", "BlackMage", "1513607516653883505", "THM");
        job("SMN", "Summoner", "1513607517652127996", "ACN");
        job("RDM", "RedMage", "1513607510332932096");
        job("PCT", "Pictomancer", "1513607504502980705");
    }

    private static void job(String code, String name, String id, String... aliases) {
        String emoji = "<:" + name + ":" + id + ">";
        JOB_EMOJI.put(code, emoji);
        for (String alias : aliases) {
            JOB_EMOJI.put(alias, emoji);
        }
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

        for (String message : buildMessages(filtered, sel)) {
            ctx.reply(message);
        }
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
     * Monta a resposta em texto puro (estilo trappingway): um cabecalho por duty
     * (## em destaque) e cada PF numa linha com a composicao em icones, vagas,
     * autor e tempo. Quebra em varias mensagens respeitando o limite de 2000
     * chars do Discord, repetindo o cabecalho da duty quando precisa continuar.
     */
    private List<String> buildMessages(List<PfListing> listings, String sel) {
        List<String> out = new ArrayList<>();
        String header = "🗡️ **Party Finder " + DATA_CENTER + " — " + selLabel(sel) + "**\n"
                + "-# vaga aberta: 🛡️ tank · 💚 healer · ⚔️ dps\n";

        if (listings.isEmpty()) {
            out.add(header + "\nNenhum PF abertinho no " + DATA_CENTER + " agora~ (´･ω･`) tenta outra duty ou volta mais tarde ♡");
            return out;
        }

        // agrupa por duty, em ordem alfabetica
        Map<String, List<PfListing>> porDuty = new LinkedHashMap<>();
        listings.stream()
                .sorted(Comparator.comparing(l -> l.duty() == null ? "" : l.duty()))
                .forEach(l -> porDuty.computeIfAbsent(l.duty(), k -> new ArrayList<>()).add(l));

        StringBuilder sb = new StringBuilder(header);
        boolean truncou = false;

        outer:
        for (Map.Entry<String, List<PfListing>> e : porDuty.entrySet()) {
            String duty = e.getKey();
            List<PfListing> grupo = e.getValue();
            grupo.sort(Comparator.comparingInt(PfListing::filled).reversed());

            String dutyHeader = "\n## " + dutyEmoji(duty) + " " + safe(duty) + "  ·  " + grupo.size() + " PF\n";
            if (sb.length() + dutyHeader.length() > MAX_MSG_CHARS) {
                if (!flush(out, sb)) { truncou = true; break; }
                sb = new StringBuilder();
            }
            sb.append(dutyHeader);

            for (PfListing l : grupo) {
                String line = formatLine(l);
                if (sb.length() + line.length() > MAX_MSG_CHARS) {
                    if (!flush(out, sb)) { truncou = true; break outer; }
                    sb = new StringBuilder("## " + dutyEmoji(duty) + " " + safe(duty) + " (cont.)\n");
                }
                sb.append(line);
            }
        }

        if (sb.length() > 0 && out.size() < MAX_MESSAGES) {
            out.add(sb.toString());
        }

        // rodape na ultima mensagem
        int ultima = out.size() - 1;
        String rodape = "\n-# xivpf.com · " + DATA_CENTER + " · " + listings.size() + " PF"
                + (truncou ? " (alguns omitidos~ filtra por `duty:`)" : "") + " · cobertura parcial (plugin)";
        if (out.get(ultima).length() + rodape.length() <= 2000) {
            out.set(ultima, out.get(ultima) + rodape);
        }
        return out;
    }

    /** Adiciona a mensagem pronta na lista; retorna false se ja batemos o limite. */
    private static boolean flush(List<String> out, StringBuilder sb) {
        if (out.size() >= MAX_MESSAGES) {
            return false;
        }
        out.add(sb.toString());
        return true;
    }

    /** Uma linha de PF: composicao em icones + vagas, slots, autor e tempo. */
    private static String formatLine(PfListing l) {
        StringBuilder b = new StringBuilder();
        b.append(compIcons(l.comp()));
        b.append(" **").append(l.slots() == null ? "?/?" : l.slots()).append("**");
        if (l.minIL() != null && !l.minIL().isBlank() && !l.minIL().equals("0")) {
            b.append(" · iL").append(l.minIL());
        }
        if (l.creator() != null && !l.creator().isBlank()) {
            b.append(" · 👤 ").append(safe(trim(creatorName(l.creator()), 24)));
        }
        if (l.expires() != null && !l.expires().isBlank()) {
            b.append(" · ⌛ ").append(shortTime(l.expires()));
        }
        b.append("\n");
        if (l.description() != null && !l.description().isBlank()) {
            b.append("-# ").append(safe(trim(l.description(), 100))).append("\n");
        }
        return b.toString();
    }

    /**
     * Renderiza a composicao: tokens de job (ex.: "WAR") viram o emoji custom do
     * job; vagas abertas ("-T"/"-H"/"-D"/"-*") viram o role em unicode.
     */
    private static String compIcons(String comp) {
        if (comp == null || comp.isBlank()) {
            return "▫️";
        }
        StringBuilder b = new StringBuilder();
        for (String tok : comp.split(",")) {
            if (tok.isEmpty()) {
                continue;
            }
            if (tok.charAt(0) == '-') {
                char role = tok.length() > 1 ? tok.charAt(1) : '*';
                b.append(switch (role) {
                    case 'T' -> "🛡️";
                    case 'H' -> "💚";
                    case 'D' -> "⚔️";
                    default -> "▫️";
                });
            } else {
                String emoji = JOB_EMOJI.get(tok.toUpperCase(Locale.ROOT));
                b.append(emoji != null ? emoji : "⚔️");
            }
        }
        return b.toString();
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
