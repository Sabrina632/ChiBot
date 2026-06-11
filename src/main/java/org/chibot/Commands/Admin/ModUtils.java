package org.chibot.Commands.Admin;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.PrefixCommandContext;
import org.chibot.Music.MusicUi;

/** Ajudantes compartilhados pelos comandos de moderacao (ban/kick/mute). */
final class ModUtils {

    private ModUtils() {
    }

    /** ID do alvo: opcao "usuario" do slash ou primeiro argumento do prefixo (mencao ou ID). */
    static String resolveTargetId(CommandContext ctx) {
        String raw = ctx.getOption("usuario");
        if (raw == null && !ctx.getArgs().isEmpty()) {
            raw = ctx.getArgs().get(0);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().replaceAll("[<@!>]", "");
        return id.matches("\\d{15,21}") ? id : null;
    }

    /** Motivo: opcao "motivo" do slash ou argumentos do prefixo a partir de fromIndex. */
    static String resolveReason(CommandContext ctx, int fromIndex) {
        String reason = ctx.getOption("motivo");
        if (reason == null && ctx instanceof PrefixCommandContext && ctx.getArgs().size() > fromIndex) {
            reason = String.join(" ", ctx.getArgs().subList(fromIndex, ctx.getArgs().size()));
        }
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    /**
     * Confere se o autor (e a Chi) podem agir sobre o alvo, respeitando a hierarquia de cargos.
     * Retorna a mensagem de erro pronta, ou null se estiver tudo certo.
     */
    static String checkTarget(CommandContext ctx, Member target, String acao) {
        Member author = ctx.getMember();
        Member self = ctx.getGuild().getSelfMember();
        if (target.equals(author)) {
            return "Você não pode " + acao + " a si mesmo, bobinho~ (・∀・)";
        }
        if (target.equals(self)) {
            return "Ei, eu não! (｡•́︿•̀｡)";
        }
        if (!author.canInteract(target)) {
            return "Você não pode " + acao + " alguém com cargo igual ou maior que o seu~ (>_<)";
        }
        if (!self.canInteract(target)) {
            return "O cargo dessa pessoa é maior que o meu, não alcanço~ (；△；)";
        }
        return null;
    }

    /** Motivo formatado pro audit log do Discord (limite de 512 caracteres). */
    static String auditReason(CommandContext ctx, String reason) {
        String text = "Por " + ctx.getAuthor().getName()
                + (reason == null ? "" : ": " + reason);
        return text.length() > 512 ? text.substring(0, 512) : text;
    }

    /** Embed kawaii padrao das acoes de moderacao (usuario, moderador e motivo). */
    static EmbedBuilder modEmbed(String title, User target, User moderator, String reason) {
        return new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle(title)
                .setThumbnail(target.getEffectiveAvatarUrl())
                .addField("👤 Usuário", target.getAsMention(), true)
                .addField("🛡️ Moderador", moderator.getAsMention(), true)
                .addField("📝 Motivo", reason == null ? "Sem motivo informado~" : reason, false);
    }
}