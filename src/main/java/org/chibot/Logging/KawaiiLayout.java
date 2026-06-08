package org.chibot.Logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassNameOnlyAbbreviator;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Layout fofo feito a mao~ Monta cada linha do log com horario, nivel com
 * iconezinho colorido, nome curtinho do logger e a mensagem. Usa cores pastel
 * em truecolor (24-bit) pra um visual mais suave/kawaii, com fallback elegante:
 * se o terminal nao suportar, os codigos ANSI simplesmente sao ignorados.
 * Tudo em Java pra nao depender de conversionRule customizado (que e instavel
 * no logback 1.5).
 */
public class KawaiiLayout extends LayoutBase<ILoggingEvent> {

    private static final String ESC = String.valueOf((char) 27);
    private static final String RESET = ESC + "[0m";
    private static final int LOGGER_WIDTH = 16;

    // Paleta pastel~ (r, g, b) -> truecolor
    private static final String C_TIME   = fg(120, 120, 140); // cinza arroxeado discreto
    private static final String C_LOGGER = fg(150, 210, 230); // ciano suave
    private static final String C_HEART  = fg(255, 160, 200); // rosa coracao
    private static final String C_ERROR  = fg(255, 130, 140); // vermelho pastel
    private static final String C_WARN   = fg(245, 210, 130); // amarelo pastel
    private static final String C_PINK   = fg(255, 175, 210); // rosa fofo
    private static final String C_CYAN   = fg(155, 225, 235); // ciano clarinho
    private static final String C_GRAY   = fg(140, 140, 150); // cinza trace

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ClassNameOnlyAbbreviator abbreviator = new ClassNameOnlyAbbreviator();
    private final ThrowableProxyConverter throwableConverter = new ThrowableProxyConverter();

    @Override
    public void start() {
        throwableConverter.start();
        super.start();
    }

    @Override
    public void stop() {
        throwableConverter.stop();
        super.stop();
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(160);

        // horario discreto entre parenteses fofos
        sb.append(paint(C_TIME, "(" + TIME.format(Instant.ofEpochMilli(event.getTimeStamp())) + ")"))
                .append(' ');

        // nivel com iconezinho colorido
        sb.append(level(event.getLevel())).append("  ");

        // nome curtinho do logger, alinhado
        String logger = pad(abbreviator.abbreviate(event.getLoggerName()), LOGGER_WIDTH);
        sb.append(paint(C_LOGGER, logger)).append(' ');

        // coracaozinho separador e a mensagem (tingida conforme o nivel)
        sb.append(paint(C_HEART, "♡")).append("  ");
        sb.append(paint(messageColor(event.getLevel()), event.getFormattedMessage()));
        sb.append(System.lineSeparator());

        // se teve excecao, mostra o stack trace logo abaixo
        if (event.getThrowableProxy() != null) {
            sb.append(paint(C_ERROR, throwableConverter.convert(event)));
        }

        return sb.toString();
    }

    /** Rotulo do nivel com iconezinho e cor propria, com largura visual ~7 pra alinhar. */
    private static String level(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> paint(C_ERROR, "✘ ERRO ");
            case Level.WARN_INT  -> paint(C_WARN,  "⚠ AVISO");
            case Level.INFO_INT  -> paint(C_PINK,  "❀ INFO ");
            case Level.DEBUG_INT -> paint(C_CYAN,  "✦ DEBUG");
            case Level.TRACE_INT -> paint(C_GRAY,  "· TRACE");
            default              -> paint(C_GRAY,  "♡ LOG  ");
        };
    }

    /** Cor da mensagem em si: erros e avisos ganham um tom, o resto fica neutro. */
    private static String messageColor(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> C_ERROR;
            case Level.WARN_INT  -> C_WARN;
            case Level.TRACE_INT -> C_GRAY;
            default              -> RESET; // cor padrao do terminal
        };
    }

    private static String fg(int r, int g, int b) {
        return ESC + "[38;2;" + r + ";" + g + ";" + b + "m";
    }

    private static String paint(String colorCode, String text) {
        return colorCode + text + RESET;
    }

    /** Deixa a string com largura fixa: corta se passar, completa com espaco se faltar. */
    private static String pad(String text, int width) {
        if (text.length() > width) {
            return text.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}