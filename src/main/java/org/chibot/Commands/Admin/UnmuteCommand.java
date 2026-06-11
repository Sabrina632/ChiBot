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

public class UnmuteCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(UnmuteCommand.class);

    @Override
    public String getName() {
        return "unmute";
    }

    @Override
    public List<String> getAliases() {
        return List.of("desmutar");
    }

    @Override
    public String getDescription() {
        return "Tira o mute de um usuário antes da hora~ castigo perdoado! (≧◡≦)";
    }

    @Override
    public String getUsage() {
        return "unmute <@usuario>";
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
        return List.of(new OptionData(OptionType.USER, "usuario", "Quem vai ser desmutado", true));
    }

    @Override
    public void execute(CommandContext ctx) {
        String targetId = ModUtils.resolveTargetId(ctx);
        if (targetId == null) {
            ctx.reply("Me fala quem desmutar~ marca a pessoa ou manda o ID! (・∀・)");
            return;
        }

        Guild guild = ctx.getGuild();
        if (!guild.getSelfMember().hasPermission(Permission.MODERATE_MEMBERS)) {
            ctx.reply("Eu não tenho a permissão de castigar membros~ (｡•́︿•̀｡)");
            return;
        }

        ctx.deferReply();
        guild.retrieveMemberById(targetId).queue(
                target -> unmute(ctx, target),
                err -> ctx.reply("Essa pessoa não está no servidor~ (・_・;)"));
    }

    private void unmute(CommandContext ctx, Member target) {
        if (!target.isTimedOut()) {
            ctx.reply("Essa pessoa nem está mutada~ (・∀・)");
            return;
        }
        if (!ctx.getGuild().getSelfMember().canInteract(target)) {
            ctx.reply("O cargo dessa pessoa é maior que o meu, não alcanço~ (；△；)");
            return;
        }
        target.removeTimeout()
                .reason(ModUtils.auditReason(ctx, null))
                .queue(ok -> ctx.replyEmbeds(ModUtils
                                .modEmbed("ﾟ･✧ Desmutado! ✧･ﾟ", target.getUser(), ctx.getAuthor(), null)
                                .setDescription("**" + target.getUser().getName()
                                        + "** pode falar de novo~ comporta-se, hein! (≧◡≦)")
                                .build()),
                        err -> {
                            log.error("Falha ao desmutar o usuario {}", target.getId(), err);
                            ctx.reply("Não consegui desmutar~ confere minhas permissões? (；△；)");
                        });
    }
}