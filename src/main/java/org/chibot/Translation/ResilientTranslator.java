package org.chibot.Translation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * Decorator que blinda qualquer {@link Translator} contra falha de rede. Sem ele,
 * cada falha do {@link AwsTranslator} (ex.: DNS da AWS fora do ar na VPS) virava um
 * stack trace gigante por chamada, na thread do gateway do JDA — e um embed sozinho
 * dispara dezenas de chamadas.
 *
 * <p>Aqui a regra é: na primeira falha, abre um disjuntor (circuit breaker) por um
 * tempo de descanso. Enquanto aberto, devolve o texto original na hora, sem nem
 * tentar a rede — então o bot degrada pro português em silêncio em vez de travar e
 * spammar log. Passado o descanso, tenta de novo uma vez.
 */
public class ResilientTranslator implements Translator {

    private static final Logger log = LoggerFactory.getLogger(ResilientTranslator.class);

    private final Translator delegate;
    private final long cooldownMillis;
    private final LongSupplier clock;

    /** Instante (do {@code clock}) até o qual o disjuntor fica aberto. 0 = fechado. */
    private volatile long openUntil = 0;

    ResilientTranslator(Translator delegate, long cooldownMillis, LongSupplier clock) {
        this.delegate = delegate;
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
    }

    /** Embrulha o tradutor (descanso de 60s). {@code null} entra, {@code null} sai. */
    public static Translator wrap(Translator delegate) {
        if (delegate == null) {
            return null;
        }
        return new ResilientTranslator(delegate, 60_000, System::currentTimeMillis);
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        if (clock.getAsLong() < openUntil) {
            return text; // disjuntor aberto: degrada na hora, sem bater na rede.
        }
        try {
            return delegate.translate(text, sourceLang, targetLang);
        } catch (Exception e) {
            openUntil = clock.getAsLong() + cooldownMillis;
            // Uma linha enxuta por janela de descanso; o stack trace fica no DEBUG.
            log.warn("Tradução falhou ({}); pausada por {}s — tudo em pt até lá.",
                    e.getMessage(), cooldownMillis / 1000);
            log.debug("Detalhe da falha de tradução:", e);
            return text;
        }
    }
}