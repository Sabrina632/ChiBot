package org.chibot.Commands.Admin;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;

public class KickCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(KickCommand.class);

    @Override
    public String getName() {
        return "kick";
    }

    @Override
    public List<String> getAliases() {
        return List.of("expulsar");
    }

    @Override
    public String getDescription() {
        return "Expulsa um usuário do servidor~ ele pode voltar se for convidado! (・∀・)ノ";
    }

    @Override
    public String getUsage() {
        return "kick <@usuario> [motivo]";
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
        return EnumSet.of(Permission.KICK_MEMBERS);
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.USER, "usuario", "Quem vai ser expulso", true),
                new OptionData(OptionType.STRING, "motivo", "Motivo da expulsão", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        String targetId = ModUtils.resolveTargetId(ctx);
        if (targetId == null) {
            ctx.reply("Me fala quem expulsar~ marca a pessoa ou manda o ID! (・∀・)");
            return;
        }

        Guild guild = ctx.getGuild();
        if (!guild.getSelfMember().hasPermission(Permission.KICK_MEMBERS)) {
            ctx.reply("Eu não tenho a permissão de expulsar membros~ (｡•́︿•̀｡)");
            return;
        }

        String reason = ModUtils.resolveReason(ctx, 1);
        ctx.deferReply();
        guild.retrieveMemberById(targetId).queue(
                target -> kick(ctx, target, reason),
                err -> ctx.reply("Essa pessoa não está no servidor~ (・_・;)"));
    }

    private void kick(CommandContext ctx, Member target, String reason) {
        String erro = ModUtils.checkTarget(ctx, target, "expulsar");
        if (erro != null) {
            ctx.reply(erro);
            return;
        }
        ctx.getGuild().kick(target)
                .reason(ModUtils.auditReason(ctx, reason))
                .queue(ok -> ctx.replyEmbeds(ModUtils
                                .modEmbed("ﾟ･✧ Expulso! ✧･ﾟ", target.getUser(), ctx.getAuthor(), reason)
                                .setDescription("**" + target.getUser().getName()
                                        + "** foi expulso(a) do servidor~ até mais! (・∀・)ノ")
                                .build()),
                        err -> {
                            log.error("Falha ao expulsar o usuario {}", target.getId(), err);
                            ctx.reply("Não consegui expulsar~ confere minhas permissões? (；△；)");
                        });
    }
}