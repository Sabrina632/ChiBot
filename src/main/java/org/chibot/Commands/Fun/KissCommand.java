package org.chibot.Commands.Fun;

import java.util.List;

public class KissCommand extends RoleplayAction {

    @Override
    public String getName() {
        return "kiss";
    }

    @Override
    public List<String> getAliases() {
        return List.of("beijo", "beijar");
    }

    @Override
    public String getDescription() {
        return "Dá um beijinho em alguém~ (＾3＾)♡";
    }

    @Override
    protected String action() {
        return "kiss";
    }

    @Override
    protected String emoji() {
        return "😘";
    }

    @Override
    protected String title() {
        return "Beijo!";
    }

    @Override
    protected String describe(String author, String target) {
        return author + " deu um beijinho em " + target + "~ (＾3＾)♡ 😘";
    }

    @Override
    protected String describeSelf(String author) {
        return author + " mandou um beijo no espelho... que charme~ (・//ω//・)";
    }
}