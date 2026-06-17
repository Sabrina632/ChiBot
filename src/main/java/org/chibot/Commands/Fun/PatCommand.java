package org.chibot.Commands.Fun;

import java.util.List;

public class PatCommand extends RoleplayAction {

    @Override
    public String getName() {
        return "pat";
    }

    @Override
    public List<String> getAliases() {
        return List.of("cafune", "cafuné", "carinho");
    }

    @Override
    public String getDescription() {
        return "Faz um cafuné em alguém~ (´• ω •`)♡";
    }

    @Override
    protected String action() {
        return "pat";
    }

    @Override
    protected String emoji() {
        return "🥰";
    }

    @Override
    protected String title() {
        return "Cafuné!";
    }

    @Override
    protected String describe(String author, String target) {
        return author + " fez um cafuné em " + target + "~ tão fofo! (´• ω •`)♡ 🥰";
    }

    @Override
    protected String describeSelf(String author) {
        return author + " fez carinho em si mesmo~ você merece todo o cafuné! 🥰";
    }
}