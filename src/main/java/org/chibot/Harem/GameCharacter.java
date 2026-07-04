package org.chibot.Harem;

import java.util.Objects;

/**
 * Personagem de jogo vindo do Giant Bomb, pronto pra ser rolado. O {@code id}
 * ja vem negativo ({@code -idDoGiantBomb}): e o namespace que separa os
 * personagens de jogos dos de anime (AniList) na tabela de claims.
 */
public final class GameCharacter {

    private final long id;
    private final String name;
    private final String gender;
    private final String game;
    private final String imageUrl;
    private final int kakera;

    public GameCharacter(long id, String name, String gender,
                         String game, String imageUrl, int kakera) {
        this.id = id;
        this.name = name;
        this.gender = gender;
        this.game = game;
        this.imageUrl = imageUrl;
        this.kakera = kakera;
    }

    public long id()       { return id; }
    public String name()   { return name; }
    public String gender() { return gender; }
    public String game()   { return game; }
    public String imageUrl() { return imageUrl; }
    public int kakera()    { return kakera; }

    public boolean isFemale() {
        return "Female".equalsIgnoreCase(gender);
    }

    public boolean isMale() {
        return "Male".equalsIgnoreCase(gender);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GameCharacter that)) return false;
        return id == that.id && kakera == that.kakera
                && Objects.equals(name, that.name)
                && Objects.equals(gender, that.gender)
                && Objects.equals(game, that.game)
                && Objects.equals(imageUrl, that.imageUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, gender, game, imageUrl, kakera);
    }

    @Override
    public String toString() {
        return "GameCharacter[id=" + id + ", name=" + name
                + ", gender=" + gender + ", game=" + game
                + ", imageUrl=" + imageUrl + ", kakera=" + kakera + "]";
    }
}
