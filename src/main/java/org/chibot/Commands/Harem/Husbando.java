package org.chibot.Commands.Harem;

import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Harem.HaremService;

import java.util.List;

public class Husbando implements ICommand {

    @Override
    public String getName() {
        return "husbando";
    }

    @Override
    public List<String> getAliases() {
        return List.of("h", "ha");
    }

    @Override
    public List<String> getSlashAliases() {
        return List.of("ha");
    }

    @Override
    public String getDescription() {
        return "Rola um husbando aleatório~ clica no 💗 pra casar! (✿◠‿◠)";
    }

    @Override
    public String getUsage() {
        return "husbando";
    }

    @Override
    public String getCategory() {
        return "Harém";
    }

    @Override
    public boolean isGuildOnly() {
        return true;
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }
        service.roll(ctx, HaremService.Genero.HUSBANDO);
    }
}