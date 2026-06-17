package org.chibot.Commands.Fun;

import java.util.List;

public class SlapCommand extends RoleplayAction {

    @Override
    public String getName() {
        return "slap";
    }

    @Override
    public List<String> getAliases() {
        return List.of("tapa", "tapar");
    }

    @Override
    public String getDescription() {
        return "Dá um tapa (de brincadeira!) em alguém~ (╯°□°）╯";
    }

    @Override
    protected String action() {
        return "slap";
    }

    @Override
    protected String emoji() {
        return "👋💢";
    }

    @Override
    protected String title() {
        return "Tapa!";
    }

    @Override
    protected String describe(String author, String target) {
        return author + " deu um tapa em " + target + "~ ai! foi de brincadeira, tá? 👋💢";
    }

    @Override
    protected String describeSelf(String author) {
        return author + " se deu um tapa sozinho... calma aí, respira! (＃｀д´)ﾉ";
    }
}