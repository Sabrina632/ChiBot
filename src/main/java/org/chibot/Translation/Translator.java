package org.chibot.Translation;

/**
 * Abstração da chamada de tradução. A implementação real ({@link DeepLTranslator})
 * fala com a API da DeepL; nos testes, um fake permite verificar o cache e a
 * máscara sem bater na rede.
 */
public interface Translator {

    /**
     * Traduz {@code text} de {@code sourceLang} para {@code targetLang}. Em caso de
     * falha, a implementação deve devolver o próprio {@code text} (degrada).
     */
    String translate(String text, String sourceLang, String targetLang);
}