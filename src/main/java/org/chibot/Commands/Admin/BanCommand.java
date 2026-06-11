package org.chibot.Commands.Admin;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BanCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(BanCommand.class);

    @Override
    public String getName() {
        return "ban";
    }

    @Override
    public List<String> getAliases() {
        return List.of("banir");
    }

    @Override
    public String getDescription() {
        return "Bane um usuário do servidor~ tchauzinho pra sempre! (ﾉ´･ω･)ﾉ";
    }

    @Override
    public String getUsage() {
        return "ban <@usuario> [motivo]";
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
        return EnumSet.of(Permission.BAN_MEMBERS);
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.USER, "usuario", "Quem vai ser banido", true),
                new OptionData(OptionType.STRING, "motivo", "Motivo do banimento", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        String targetId = ModUtils.resolveTargetId(ctx);
        if (targetId == null) {
            ctx.reply("Me fala quem banir~ marca a pessoa ou manda o ID! (・∀・)");
            return;
        }

        Guild guild = ctx.getGuild();
        if (!guild.getSelfMember().hasPermission(Permission.BAN_MEMBERS)) {
            ctx.reply("Eu não tenho a permissão de banir membros~ (｡•́︿•̀｡)");
            return;
        }

        String reason = ModUtils.resolveReason(ctx, 1);
        ctx.deferReply();
        // Quem nao esta no servidor tambem pode ser banido (ban preventivo por ID).
        guild.retrieveMemberById(targetId).queue(
                target -> banMember(ctx, target, reason),
                err -> banById(ctx, targetId, reason));
    }

    private void banMember(CommandContext ctx, Member target, String reason) {
        String erro = ModUtils.checkTarget(ctx, target, "banir");
        if (erro != null) {
            ctx.reply(erro);
            return;
        }
        ban(ctx, target.getUser(), reason);
    }

    private void banById(CommandContext ctx, String targetId, String reason) {
        ctx.getJDA().retrieveUserById(targetId).queue(
                user -> ban(ctx, user, reason),
                err -> ctx.reply("Não achei esse usuário em lugar nenhum~ (・_・;)"));
    }

    private void ban(CommandContext ctx, User target, String reason) {
        ctx.getGuild().ban(target, 0, TimeUnit.SECONDS)
                .reason(ModUtils.auditReason(ctx, reason))
                .queue(ok -> ctx.replyEmbeds(ModUtils
                                .modEmbed("ﾟ･✧ Banido! ✧･ﾟ", target, ctx.getAuthor(), reason)
                                .setDescription("**" + target.getName()
                                        + "** foi banido(a) do servidor... tchauzinho! (ﾉ´･ω･)ﾉ")
                                .build()),
                        err -> {
                            log.error("Falha ao banir o usuario {}", target.getId(), err);
                            ctx.reply("Não consegui banir~ confere minhas permissões? (；△；)");
                        });
    }
}
