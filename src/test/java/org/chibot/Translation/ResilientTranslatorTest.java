package org.chibot.Translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResilientTranslatorTest {

    /** Tradutor fake controlável: pode lançar ou prefixar, e conta chamadas. */
    static class FakeTranslator implements Translator {
        int calls = 0;
        boolean throwOnCall = false;

        @Override
        public String translate(String text, String source, String target) {
            calls++;
            if (throwOnCall) {
                throw new RuntimeException("rede caiu");
            }
            return "<" + target + ">" + text;
        }
    }

    /** Relógio mutável pros testes mexerem o tempo sem dormir. */
    static class FakeClock {
        long now = 0;
        long get() {
            return now;
        }
    }

    @Test
    void delegaQuandoFunciona() {
        FakeTranslator fake = new FakeTranslator();
        ResilientTranslator r = new ResilientTranslator(fake, 60_000, new FakeClock()::get);
        assertEquals("<en>Olá", r.translate("Olá", "pt", "en"));
        assertEquals(1, fake.calls);
    }

    @Test
    void degradaParaOriginalQuandoFalha() {
        FakeTranslator fake = new FakeTranslator();
        fake.throwOnCall = true;
        ResilientTranslator r = new ResilientTranslator(fake, 60_000, new FakeClock()::get);
        assertEquals("Olá", r.translate("Olá", "pt", "en"));
        assertEquals(1, fake.calls);
    }

    @Test
    void breakerAbreNaoChamaDelegateDuranteCooldown() {
        FakeTranslator fake = new FakeTranslator();
        fake.throwOnCall = true;
        FakeClock clock = new FakeClock();
        ResilientTranslator r = new ResilientTranslator(fake, 60_000, clock::get);

        r.translate("Olá", "pt", "en");          // falha, abre o breaker (1 chamada)
        clock.now = 30_000;                       // ainda dentro do cooldown
        assertEquals("Tchau", r.translate("Tchau", "pt", "en"));
        assertEquals(1, fake.calls);              // delegate NÃO foi chamado de novo
    }

    @Test
    void breakerFechaDepoisDoCooldown() {
        FakeTranslator fake = new FakeTranslator();
        fake.throwOnCall = true;
        FakeClock clock = new FakeClock();
        ResilientTranslator r = new ResilientTranslator(fake, 60_000, clock::get);

        r.translate("Olá", "pt", "en");          // falha, abre o breaker
        clock.now = 60_001;                       // cooldown expirou
        fake.throwOnCall = false;                 // rede voltou
        assertEquals("<en>Oi", r.translate("Oi", "pt", "en"));
        assertEquals(2, fake.calls);              // tentou de novo
    }
}