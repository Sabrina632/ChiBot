package org.chibot.Harem;

/** Personagem de anime/manga vindo do AniList, pronto pra ser rolado. */
public record AnimeCharacter(
        long id,
        String name,
        String gender,
        String series,
        String imageUrl,
        int favourites,
        int kakera) {

    public boolean isFemale() {
        return "Female".equalsIgnoreCase(gender);
    }

    public boolean isMale() {
        return "Male".equalsIgnoreCase(gender);
    }
}