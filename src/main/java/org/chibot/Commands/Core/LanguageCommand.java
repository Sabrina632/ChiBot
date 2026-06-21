package org.chibot.Commands.Core;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Translation.TranslationService;

import java.util.ArrayList;
import java.util.List;

/**
 * Deixa cada pessoa escolher o idioma em que a Chi responde só pra ela.
 * {@code !language en} muda pra inglês; {@code !language} sozinho mostra o atual e
 * os suportados; {@code !language pt} volta ao padrão.
 */
public class LanguageCommand implements ICommand {

    @Override
    public String getName() {
        return "language";
    }

    @Override
    public List<String> getAliases() {
        return List.of("lang", "idioma");
    }

    @Override
    public String getDescription() {
        return "Escolhe o idioma em que eu falo só com você~ 🌎";
    }

    @Override
    public String getUsage() {
        return "language [código]";
    }

    @Override
    public String getCategory() {
        return "Core";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "codigo",
                "Código do idioma (ex.: en, es, ja). Vazio = mostra o atual.", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        TranslationService ts = TranslationService.get();
        if (ts == null) {
            ctx.reply("O sistema de idiomas ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String userId = ctx.getAuthor().getId();
        String code = resolveCodigo(ctx);

        if (code == null || code.isBlank()) {
            String atual = ts.getLanguage(userId);
            ctx.reply("Seu idioma agora é **" + atual + "**~ ♡\n"
                    + "Pra trocar, use `language <código>`. Suportados: " + listaSuportados(ts));
            return;
        }

        code = code.toLowerCase();
        if (!ts.setLanguage(userId, code)) {
            ctx.reply("Não conheço o idioma **" + code + "**~ (｡•́︿•̀｡)\n"
                    + "Os que eu falo: " + listaSuportados(ts));
            return;
        }

        // A confirmação passa pelo context, que já traduz pro idioma recém-escolhido.
        ctx.reply("Pronto~ agora eu falo **" + code + "** só com você! (｡•̀ᴗ-)✧");
    }

    /** Código pedido: opção "codigo" do slash ou primeiro argumento do prefixo. */
    private String resolveCodigo(CommandContext ctx) {
        String raw = ctx.getOption("codigo");
        if ((raw == null || raw.isBlank()) && !ctx.getArgs().isEmpty()) {
            raw = ctx.getArgs().get(0);
        }
        return raw;
    }

    private String listaSuportados(TranslationService ts) {
        List<String> codes = new ArrayList<>(ts.supportedLanguages());
        codes.sort(String::compareTo);
        return "`" + String.join("`, `", codes) + "`";
    }
}