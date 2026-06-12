package org.chibot.Commands.Harem;

import org.chibot.Database.HaremRepository;

import java.util.List;

/** Ajudantes compartilhados pelos comandos do harem (divorce/trade/harem). */
final class HaremUtils {

    private HaremUtils() {
    }

    /** Prefere o nome exato; aceita match parcial somente quando e unico. */
    static HaremRepository.Claim escolher(List<HaremRepository.Claim> matches, String nome) {
        for (HaremRepository.Claim c : matches) {
            if (c.name().equalsIgnoreCase(nome)) {
                return c;
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Extrai o ID de uma mencao ({@code <@123>}) ou ID puro; null se nao for nenhum dos dois. */
    static String resolveUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().replaceAll("[<@!>]", "");
        return id.matches("\\d{15,21}") ? id : null;
    }
}