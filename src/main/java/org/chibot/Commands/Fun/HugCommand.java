package org.chibot.Commands.Fun;

import java.util.List;

public class HugCommand extends RoleplayAction {

    @Override
    public String getName() {
        return "hug";
    }

    @Override
    public List<String> getAliases() {
        return List.of("abraco", "abracar", "abraço");
    }

    @Override
    public String getDescription() {
        return "Dá um abraço apertadinho em alguém~ (づ｡◕‿‿◕｡)づ";
    }

    @Override
    protected String action() {
        return "hug";
    }

    @Override
    protected String emoji() {
        return "🤗";
    }

    @Override
    protected String title() {
        return "Abraço!";
    }

    @Override
    protected String describe(String author, String target) {
        return author + " deu um abraço apertadinho em " + target + "~ que fofo! 🤗";
    }

    @Override
    protected String describeSelf(String author) {
        return author + " se abraçou sozinho... vem cá, eu te abraço! (づ｡◕‿‿◕｡)づ";
    }
}