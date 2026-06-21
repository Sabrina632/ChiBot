package org.chibot.Commands.Core;

/**
 * Codec do ID dos botões do {@code !help}. O ID carrega tudo (sem estado em
 * memória), então os botões funcionam mesmo depois do bot reiniciar:
 *
 * <ul>
 *   <li>{@code help:home:<invocadorId>} — volta pro overview;</li>
 *   <li>{@code help:cat:<invocadorId>:<categoria>} — mostra uma categoria.</li>
 * </ul>
 *
 * <p>O {@code invocadorId} vem antes da categoria, então a categoria (o resto) pode
 * conter qualquer caractere (espaço, acento) sem atrapalhar o parse.
 */
public final class HelpButtonId {

    public static final String PREFIX = "help:";

    private HelpButtonId() {
    }

    public record Parsed(String action, String invokerId, String category) {
    }

    public static String home(String invokerId) {
        return PREFIX + "home:" + invokerId;
    }

    public static String cat(String invokerId, String categoria) {
        return PREFIX + "cat:" + invokerId + ":" + categoria;
    }

    /** Decodifica um ID de botão do help, ou {@code null} se não for um (ou for malformado). */
    public static Parsed decode(String id) {
        if (id == null || !id.startsWith(PREFIX)) {
            return null;
        }
        String[] p = id.substring(PREFIX.length()).split(":", 3);
        if (p.length < 2 || p[1].isBlank()) {
            return null;
        }
        String action = p[0];
        String invokerId = p[1];
        return switch (action) {
            case "home" -> new Parsed("home", invokerId, null);
            case "cat" -> (p.length == 3 && !p[2].isBlank())
                    ? new Parsed("cat", invokerId, p[2]) : null;
            default -> null;
        };
    }
}