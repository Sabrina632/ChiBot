package org.chibot.Commands.Music;

import dev.arbjerg.lavalink.client.player.Track;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Database.MusicRepository;
import org.chibot.Music.GuildMusicManager;
import org.chibot.Music.MusicService;
import org.chibot.Music.MusicUi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PlaylistCommand extends MusicCommand {

    /** Tamanho maximo do nome de uma playlist salva. */
    private static final int MAX_NAME = 80;

    @Override
    public String getName() {
        return "playlist";
    }

    @Override
    public List<String> getAliases() {
        return List.of("queue", "fila", "q");
    }

    @Override
    public String getDescription() {
        return "Mostra a fila, adiciona músicas e guarda playlists suas pra reusar~ (´｡• ᵕ •｡`)";
    }

    @Override
    public String getUsage() {
        return "playlist [add <link>|salvar <nome>|carregar <nome>|apagar <nome>|salvas]";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.STRING, "add",
                        "Nome ou link de uma playlist do YouTube (entram até 100 músicas)", false),
                new OptionData(OptionType.STRING, "salvar",
                        "Salva a fila atual como uma playlist sua com esse nome", false),
                new OptionData(OptionType.STRING, "carregar",
                        "Toca uma playlist sua já salva com esse nome", false),
                new OptionData(OptionType.STRING, "apagar",
                        "Apaga uma playlist sua salva com esse nome", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        // Slash: cada acao e uma opcao. Prefixo: a acao e o primeiro argumento.
        String add = argFor(ctx, "add", "add");
        if (add != null) {
            if (add.isBlank()) {
                ctx.reply("Me fala o que adicionar~ o nome ou o link de uma playlist! (・∀・)");
                return;
            }
            loadAndPlayPlaylistQuery(ctx, add);
            return;
        }

        String salvar = argFor(ctx, "salvar", "salvar", "save");
        if (salvar != null) {
            savePlaylist(ctx, salvar);
            return;
        }

        String carregar = argFor(ctx, "carregar", "carregar", "load", "tocar");
        if (carregar != null) {
            loadSavedPlaylist(ctx, carregar);
            return;
        }

        String apagar = argFor(ctx, "apagar", "apagar", "delete", "remover");
        if (apagar != null) {
            deletePlaylist(ctx, apagar);
            return;
        }

        // Prefixo: "salvas"/"lista" lista as playlists do autor.
        List<String> args = ctx.getArgs();
        if (!args.isEmpty() && isVerb(args.get(0), "salvas", "lista", "list", "minhas")) {
            listPlaylists(ctx);
            return;
        }

        showQueue(ctx);
    }

    // --------------------------------------------------------------- playlists

    private void savePlaylist(CommandContext ctx, String nome) {
        String name = nome.trim();
        if (name.isBlank()) {
            ctx.reply("Me fala um nome pra playlist~ (・∀・)");
            return;
        }
        if (name.length() > MAX_NAME) {
            ctx.reply("Esse nome é grandão demais~ usa até " + MAX_NAME + " caracteres! (>_<)");
            return;
        }

        GuildMusicManager manager = getManager(ctx);
        Optional<Track> current = manager.getCurrentTrack();
        if (current.isEmpty()) {
            ctx.reply("Não tô tocando nada pra salvar~ bota umas músicas na fila primeiro! (｡•́︿•̀｡)");
            return;
        }

        List<Track> all = new ArrayList<>();
        all.add(current.get());
        all.addAll(manager.getQueueSnapshot());
        List<MusicRepository.StoredTrack> stored = new ArrayList<>();
        for (Track t : all) {
            stored.add(new MusicRepository.StoredTrack(t.getEncoded(), t.getInfo().getTitle()));
        }

        boolean ok = MusicService.get().getRepo()
                .savePlaylist(ctx.getGuild().getId(), ctx.getAuthor().getId(),
                        name, stored, System.currentTimeMillis());
        if (!ok) {
            ctx.reply("Não consegui salvar a playlist agora~ (；△；)");
            return;
        }
        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Playlist salva! ✧･ﾟ")
                .setDescription("Guardei **" + stored.size() + "** musiquinha(s) em **" + name
                        + "**~ usa `playlist carregar " + name + "` quando quiser! (≧◡≦) ♡")
                .build());
    }

    private void loadSavedPlaylist(CommandContext ctx, String nome) {
        String name = nome.trim();
        if (name.isBlank()) {
            ctx.reply("Me fala o nome da playlist que você quer tocar~ (・∀・)");
            return;
        }

        List<MusicRepository.StoredTrack> stored = MusicService.get().getRepo()
                .loadPlaylist(ctx.getGuild().getId(), ctx.getAuthor().getId(), name);
        if (stored.isEmpty()) {
            ctx.reply("Não achei uma playlist sua chamada **" + name
                    + "**~ vê as suas com `playlist salvas`! (・_・;)");
            return;
        }

        GuildMusicManager manager = preparePlayback(ctx);
        if (manager == null) {
            return; // sem canal de voz; preparePlayback ja avisou
        }
        List<String> encoded = stored.stream().map(MusicRepository.StoredTrack::encoded).toList();
        manager.enqueueEncoded(encoded,
                tracks -> {
                    EmbedBuilder embed = new EmbedBuilder()
                            .setColor(MusicUi.KAWAII_PINK)
                            .setTitle("ﾟ･✧ Playlist na fila! ✧･ﾟ")
                            .setDescription("Coloquei **" + tracks.size() + "** musiquinha(s) de **"
                                    + name + "**~ (≧◡≦) ♡");
                    Track cur = manager.getCurrentTrack().orElse(tracks.get(0));
                    MusicUi.withNowPlaying(embed, cur, manager.getQueueSnapshot());
                    ctx.replyEmbeds(embed.build());
                },
                err -> ctx.reply("Deu ruim pra carregar essa playlist~ (；△；)"));
    }

    private void deletePlaylist(CommandContext ctx, String nome) {
        String name = nome.trim();
        if (name.isBlank()) {
            ctx.reply("Me fala o nome da playlist que você quer apagar~ (・∀・)");
            return;
        }
        boolean removed = MusicService.get().getRepo()
                .deletePlaylist(ctx.getGuild().getId(), ctx.getAuthor().getId(), name);
        ctx.reply(removed
                ? "Apaguei a playlist **" + name + "**~ 🗑️"
                : "Não achei uma playlist sua chamada **" + name + "**~ (・_・;)");
    }

    private void listPlaylists(CommandContext ctx) {
        List<MusicRepository.SavedPlaylist> playlists = MusicService.get().getRepo()
                .listPlaylists(ctx.getGuild().getId(), ctx.getAuthor().getId());
        if (playlists.isEmpty()) {
            ctx.reply("Você ainda não salvou nenhuma playlist~ usa `playlist salvar <nome>`! (・∀・)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (MusicRepository.SavedPlaylist p : playlists) {
            sb.append("♡ **").append(p.name()).append("** — ")
                    .append(p.trackCount()).append(" musiquinha(s)\n");
        }
        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Suas playlists salvas ✧･ﾟ")
                .setDescription(sb.toString())
                .setFooter("Toca com playlist carregar <nome> ♪")
                .build());
    }

    private void showQueue(CommandContext ctx) {
        GuildMusicManager manager = getManager(ctx);
        var current = manager.getCurrentTrack();
        if (current.isEmpty()) {
            ctx.reply("Não tô tocando nada agora~ usa o play pra começar! (・∀・)");
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Fila de musiquinhas ✧･ﾟ");

        List<Track> queue = manager.getQueueSnapshot();
        if (queue.isEmpty()) {
            embed.setDescription("A fila tá vazia~ aproveita a música! ♡");
        }
        MusicUi.withNowPlaying(embed, current.get(), queue);

        ctx.replyEmbeds(embed.build());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolve o argumento de uma acao: no slash vem da opcao {@code optionName};
     * no prefixo, de {@code !playlist <verbo> <resto>} se o primeiro arg casar com
     * algum dos {@code verbs}. Null = essa acao nao foi pedida.
     */
    private static String argFor(CommandContext ctx, String optionName, String... verbs) {
        String option = ctx.getOption(optionName);
        if (option != null) {
            return option;
        }
        List<String> args = ctx.getArgs();
        if (!args.isEmpty() && isVerb(args.get(0), verbs)) {
            return String.join(" ", args.subList(1, args.size()));
        }
        return null;
    }

    private static boolean isVerb(String arg, String... verbs) {
        for (String v : verbs) {
            if (arg.equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }
}
