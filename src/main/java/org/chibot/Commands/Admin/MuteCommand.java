package org.chibot.Commands.Admin;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Commands.PrefixCommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Castigo (timeout) do Discord: o usuario mutado nao consegue falar nos canais
 * de voz nem mandar mensagem no chat, e o mute expira sozinho no fim da duracao.
 */
public class MuteCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(MuteCommand.class);

    private static final Duration DEFAULT_DURATION = Duration.ofMinutes(10);
    /** Limite do timeout do Discord. */
    private static final Duration MAX_DURATION = Duration.ofDays(28);
    /** Numero + unidade opcional: 30s, 10m, 2h, 7d (sem unidade = minutos). */
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)^(\\d{1,6})([smhd])?$");

    @Override
    public String getName() {
        return "mute";
    }

    @Override
    public List<String> getAliases() {
        return List.of("mutar", "silenciar", "castigo");
    }

    @Override
    public String getDescription() {
        return "Muta um usuário~ sem falar na voz nem no chat por um tempinho! (｡-_-｡)";
    }

    @Override
    public String getUsage() {
        return "mute <@usuario> [duração] [motivo] — duração tipo 30s, 10m, 2h, 7d (padrão 10m)";
    }

    @Override
    public String getCategory() {
        return "Moderação";
    }

    @Override
    public boolean isGuildOnly() {
        return true;
    }

    @Override
    public EnumSet<Permission> getRequiredPermissions() {
        return EnumSet.of(Permission.MODERATE_MEMBERS);
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.USER, "usuario", "Quem vai ser mutado", true),
                new OptionData(OptionType.STRING, "duracao",
                        "Por quanto tempo: 30s, 10m, 2h, 7d... (padrão 10m, máximo 28d)", false),
                new OptionData(OptionType.STRING, "motivo", "Motivo do mute", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        String targetId = ModUtils.resolveTargetId(ctx);
        if (targetId == null) {
            ctx.reply("Me fala quem mutar~ marca a pessoa ou manda o ID! (・∀・)");
            return;
        }

        Guild guild = ctx.getGuild();
        if (!guild.getSelfMember().hasPermission(Permission.MODERATE_MEMBERS)) {
            ctx.reply("Eu não tenho a permissão de castigar membros~ (｡•́︿•̀｡)");
            return;
        }

        // No prefixo a duracao e o segundo argumento (se parecer uma duracao);
        // o que sobra vira o motivo. No slash cada coisa tem sua opcao.
        Duration duration = DEFAULT_DURATION;
        int reasonFrom = 1;
        if (ctx instanceof PrefixCommandContext) {
            if (ctx.getArgs().size() > 1) {
                Duration parsed = parseDuration(ctx.getArgs().get(1));
                if (parsed != null) {
                    duration = parsed;
                    reasonFrom = 2;
                }
            }
        } else {
            String raw = ctx.getOption("duracao");
            if (raw != null) {
                Duration parsed = parseDuration(raw);
                if (parsed == null) {
                    ctx.reply("Não entendi essa duração~ usa algo como 30s, 10m, 2h ou 7d! (・∀・)");
                    return;
                }
                duration = parsed;
            }
        }
        if (duration.isZero()) {
            ctx.reply("Mutar por zero segundos não muta nada, né~ (・∀・)");
            return;
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            ctx.reply("O Discord só deixa mutar por até 28 dias~ (>_<)");
            return;
        }

        String reason = ModUtils.resolveReason(ctx, reasonFrom);
        Duration finalDuration = duration;
        ctx.deferReply();
        guild.retrieveMemberById(targetId).queue(
                target -> mute(ctx, target, finalDuration, reason),
                err -> ctx.reply("Essa pessoa não está no servidor~ (・_・;)"));
    }

    private void mute(CommandContext ctx, Member target, Duration duration, String reason) {
        String erro = ModUtils.checkTarget(ctx, target, "mutar");
        if (erro != null) {
            ctx.reply(erro);
            return;
        }
        long endsAt = Instant.now().plus(duration).getEpochSecond();
        target.timeoutFor(duration)
                .reason(ModUtils.auditReason(ctx, reason))
                .queue(ok -> ctx.replyEmbeds(ModUtils
                                .modEmbed("ﾟ･✧ Mutado! ✧･ﾟ", target.getUser(), ctx.getAuthor(), reason)
                                .setDescription("**" + target.getUser().getName()
                                        + "** está de castigo~ sem falar na voz nem no chat! (｡-_-｡)")
                                .addField("⏰ Duração",
                                        formatDuration(duration) + " (termina <t:" + endsAt + ":R>)", false)
                                .build()),
                        err -> {
                            log.error("Falha ao mutar o usuario {}", target.getId(), err);
                            ctx.reply("Não consegui mutar~ confere minhas permissões? (；△；)");
                        });
    }

    /** Converte "30s", "10m", "2h", "7d" (ou so numero = minutos) em Duration; null se invalida. */
    static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = DURATION_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return null;
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) == null ? "m" : matcher.group(2).toLowerCase();
        return switch (unit) {
            case "s" -> Duration.ofSeconds(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> Duration.ofMinutes(amount);
        };
    }

    /** Formata a duracao de um jeito legivel, ex.: "1d 2h 30m". */
    static String formatDuration(Duration duration) {
        StringBuilder sb = new StringBuilder();
        if (duration.toDays() > 0) {
            sb.append(duration.toDays()).append("d ");
        }
        if (duration.toHoursPart() > 0) {
            sb.append(duration.toHoursPart()).append("h ");
        }
        if (duration.toMinutesPart() > 0) {
            sb.append(duration.toMinutesPart()).append("m ");
        }
        if (duration.toSecondsPart() > 0) {
            sb.append(duration.toSecondsPart()).append("s");
        }
        return sb.toString().trim();
    }
}