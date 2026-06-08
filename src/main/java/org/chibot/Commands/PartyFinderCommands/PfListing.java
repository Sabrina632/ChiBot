package org.chibot.Commands.PartyFinderCommands;

/**
 * Uma listagem do Party Finder, do jeitinho que vem do xivpf.com.
 * Campos que podem faltar vem como null/0 — a fonte e crowdsourced.
 */
public record PfListing(
        String id,
        String dataCentre,
        String category,
        String duty,
        String description,
        String slots,   // ex.: "5/8"
        int filled,
        int total,
        String minIL,
        String creator, // "Nome @ MundoNatal"
        String world,   // mundo onde o PF foi criado
        String expires, // ex.: "in 30 minutes"
        String updated, // ex.: "now"
        String comp     // composicao em ordem: T=tank, H=healer, D=dps, -=vaga (ex.: "THDDD---")
) {

    /** Ultimates tem "(Ultimate)" no nome da duty. */
    public boolean isUltimate() {
        return duty != null && duty.contains("(Ultimate)");
    }

    /** Savages tem "(Savage)" no nome da duty. */
    public boolean isSavage() {
        return duty != null && duty.contains("(Savage)");
    }
}