package org.chibot.Harem;

/**
 * Personagem de jogo vindo do Giant Bomb, pronto pra ser rolado. O {@code id}
 * ja vem negativo ({@code -idDoGiantBomb}): e o namespace que separa os
 * personagens de jogos dos de anime (AniList) na tabela de claims.
 */
public record GameCharacter(
        long id,
        String name,
        String gender,
        String game,
        String imageUrl,
        int kakera) {

    public boolean isFemale() {
        return "Female".equalsIgnoreCase(gender);
    }

    public boolean isMale() {
        return "Male".equalsIgnoreCase(gender);
    }
}
