package org.chibot.Commands.PartyFinderCommands;

import java.util.Map;

/**
 * Mapa mundo -> data center (porte do {@code scraper/worlds.py} do
 * xivpf-tokenizer). A API do xivpf so traz o mundo da listagem; daqui sai o DC.
 */
final class XivpfWorlds {

    private XivpfWorlds() {}

    static final Map<String, String> WORLD_TO_DC = Map.ofEntries(
            // NA — Aether
            Map.entry("Adamantoise", "Aether"), Map.entry("Cactuar", "Aether"),
            Map.entry("Faerie", "Aether"), Map.entry("Gilgamesh", "Aether"),
            Map.entry("Jenova", "Aether"), Map.entry("Midgardsormr", "Aether"),
            Map.entry("Sargatanas", "Aether"), Map.entry("Siren", "Aether"),
            // NA — Crystal
            Map.entry("Balmung", "Crystal"), Map.entry("Brynhildr", "Crystal"),
            Map.entry("Coeurl", "Crystal"), Map.entry("Diabolos", "Crystal"),
            Map.entry("Goblin", "Crystal"), Map.entry("Malboro", "Crystal"),
            Map.entry("Mateus", "Crystal"), Map.entry("Zalera", "Crystal"),
            // NA — Dynamis
            Map.entry("Cuchulainn", "Dynamis"), Map.entry("Golem", "Dynamis"),
            Map.entry("Halicarnassus", "Dynamis"), Map.entry("Kraken", "Dynamis"),
            Map.entry("Maduin", "Dynamis"), Map.entry("Marilith", "Dynamis"),
            Map.entry("Rafflesia", "Dynamis"), Map.entry("Seraph", "Dynamis"),
            // NA — Primal
            Map.entry("Behemoth", "Primal"), Map.entry("Excalibur", "Primal"),
            Map.entry("Exodus", "Primal"), Map.entry("Famfrit", "Primal"),
            Map.entry("Hyperion", "Primal"), Map.entry("Lamia", "Primal"),
            Map.entry("Leviathan", "Primal"), Map.entry("Ultros", "Primal"),
            // EU — Chaos
            Map.entry("Cerberus", "Chaos"), Map.entry("Louisoix", "Chaos"),
            Map.entry("Moogle", "Chaos"), Map.entry("Omega", "Chaos"),
            Map.entry("Phantom", "Chaos"), Map.entry("Ragnarok", "Chaos"),
            Map.entry("Sagittarius", "Chaos"), Map.entry("Spriggan", "Chaos"),
            // EU — Light
            Map.entry("Alpha", "Light"), Map.entry("Lich", "Light"),
            Map.entry("Odin", "Light"), Map.entry("Phoenix", "Light"),
            Map.entry("Raiden", "Light"), Map.entry("Shiva", "Light"),
            Map.entry("Twintania", "Light"), Map.entry("Zodiark", "Light"),
            // JP — Elemental
            Map.entry("Aegis", "Elemental"), Map.entry("Atomos", "Elemental"),
            Map.entry("Carbuncle", "Elemental"), Map.entry("Garuda", "Elemental"),
            Map.entry("Gungnir", "Elemental"), Map.entry("Kujata", "Elemental"),
            Map.entry("Tonberry", "Elemental"), Map.entry("Typhon", "Elemental"),
            // JP — Gaia
            Map.entry("Alexander", "Gaia"), Map.entry("Bahamut", "Gaia"),
            Map.entry("Durandal", "Gaia"), Map.entry("Fenrir", "Gaia"),
            Map.entry("Ifrit", "Gaia"), Map.entry("Ridill", "Gaia"),
            Map.entry("Tiamat", "Gaia"), Map.entry("Ultima", "Gaia"),
            // JP — Mana
            Map.entry("Anima", "Mana"), Map.entry("Asura", "Mana"),
            Map.entry("Chocobo", "Mana"), Map.entry("Hades", "Mana"),
            Map.entry("Ixion", "Mana"), Map.entry("Masamune", "Mana"),
            Map.entry("Pandaemonium", "Mana"), Map.entry("Titan", "Mana"),
            // JP — Meteor
            Map.entry("Belias", "Meteor"), Map.entry("Mandragora", "Meteor"),
            Map.entry("Ramuh", "Meteor"), Map.entry("Shinryu", "Meteor"),
            Map.entry("Unicorn", "Meteor"), Map.entry("Valefor", "Meteor"),
            Map.entry("Yojimbo", "Meteor"), Map.entry("Zeromus", "Meteor"),
            // OCE — Materia
            Map.entry("Bismarck", "Materia"), Map.entry("Ravana", "Materia"),
            Map.entry("Sephirot", "Materia"), Map.entry("Sophia", "Materia"),
            Map.entry("Zurvan", "Materia")
    );
}