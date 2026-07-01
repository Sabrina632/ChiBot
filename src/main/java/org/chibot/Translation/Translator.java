package org.chibot.Translation;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Traduz vários textos de uma vez, preservando a ordem. O default chama
     * {@link #translate(String, String, String)} um a um; implementações que
     * suportam lote de verdade ({@link DeepLTranslator}) sobrescrevem pra fazer
     * uma única chamada de rede — importante porque um embed tem vários campos
     * e a tradução roda na thread de eventos do JDA.
     */
    default List<String> translate(List<String> texts, String sourceLang, String targetLang) {
        List<String> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(translate(text, sourceLang, targetLang));
        }
        return out;
    }
}