package org.chibot.Commands.Admin;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Config.ChiConfig;
import org.chibot.Database.MaintenanceRepository;
import org.chibot.Music.MusicUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Comando do dono: liga/desliga o modo manutencao. Com ele ligado, o
 * {@link org.chibot.Commands.CommandListener} pausa todos os outros comandos
 * (so o proprio maintenance continua respondendo, pro dono conseguir desligar).
 * So o dono — identificado pelo ID — pode usar.
 */
public class MaintenanceCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceCommand.class);

    /** Flag global lida pelo CommandListener pra saber se deve pausar os comandos. */
    private static volatile boolean active = false;

    /**
     * Banco onde o flag é persistido pra sobreviver a restart. Setado no boot
     * por {@link #init(MaintenanceRepository)}; null = só em memória (degrada).
     */
    private static volatile MaintenanceRepository repository;

    /**
     * Liga a persistência e restaura o estado salvo. Chamado uma vez no boot
     * (antes do JDA subir), pro bot voltar como estava: se a manutenção ficou
     * ligada no último desligamento, ela continua ligada agora.
     */
    public static void init(MaintenanceRepository repo) {
        repository = repo;
        if (repo != null) {
            active = repo.isMaintenanceActive();
            if (active) {
                log.info("Modo manutenção restaurado do banco: ainda LIGADO.");
            }
        }
    }

    /** Se o modo manutencao esta ligado (todos os comandos pausados). */
    public static boolean isActive() {
        return active;
    }

    /**
     * So o dono pode usar este comando. O ID vem do .env (OWNER_ID), entao nao
     * fica exposto no fonte. Vazio/nao configurado = ninguem tem acesso.
     */
    public static boolean isOwner(String userId) {
        ChiConfig config = ChiConfig.get();
        if (config == null) {
            return false;
        }
        String ownerId = config.getOwnerId();
        return ownerId != null && !ownerId.isBlank() && ownerId.equals(userId);
    }

    @Override
    public String getName() {
        return "maintenance";
    }

    @Override
    public List<String> getAliases() {
        return List.of("manutencao", "manutenção");
    }

    @Override
    public String getDescription() {
        return "Coloca a Chi em manutenção e pausa todos os comandos~ (só o dono) (｡•̀ᴗ-)✧";
    }

    @Override
    public String getUsage() {
        return "maintenance [on|off]";
    }

    @Override
    public String getCategory() {
        return "Dono";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "estado",
                "on pra ligar, off pra desligar (vazio = alterna)", false)
                .addChoice("on", "on")
                .addChoice("off", "off")
                .addChoice("status", "status"));
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!isOwner(ctx.getAuthor().getId())) {
            ctx.reply("Esse comando é só do meu dono~ (｡•́︿•̀｡)");
            return;
        }

        String estado = resolveEstado(ctx);
        boolean target;
        switch (estado) {
            case "on" -> target = true;
            case "off" -> target = false;
            case "status" -> {
                replyStatus(ctx);
                return;
            }
            default -> target = !active; // sem argumento: alterna
        }

        active = target;
        if (repository != null) {
            repository.setMaintenanceActive(active);
        }
        log.info("Modo manutenção {} por {}", active ? "LIGADO" : "DESLIGADO", ctx.getAuthor().getId());

        EmbedBuilder embed = new EmbedBuilder().setColor(MusicUi.KAWAII_PINK);
        if (active) {
            embed.setTitle("ﾟ･✧ Modo manutenção ligado ✧･ﾟ")
                    .setDescription("Vou tirar uma sonequinha pra arrumar as coisas~ "
                            + "todos os comandos estão pausados até você me chamar de volta! (˘ω˘)\n\n"
                            + "Use `maintenance off` quando terminar~ ♡");
        } else {
            embed.setTitle("ﾟ･✧ Voltei! ✧･ﾟ")
                    .setDescription("Manutenção encerrada~ já posso responder todo mundo de novo! (≧◡≦) ♡");
        }
        ctx.replyEmbeds(embed.build());
    }

    private void replyStatus(CommandContext ctx) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Status da manutenção ✧･ﾟ")
                .setDescription(active
                        ? "Estou em manutenção agora~ comandos pausados. (˘ω˘)"
                        : "Tudo funcionando normalmente~ (≧◡≦) ♡");
        ctx.replyEmbeds(embed.build());
    }

    /** Estado pedido: opcao "estado" do slash ou primeiro argumento do prefixo. */
    private String resolveEstado(CommandContext ctx) {
        String raw = ctx.getOption("estado");
        if ((raw == null || raw.isBlank()) && !ctx.getArgs().isEmpty()) {
            raw = ctx.getArgs().get(0);
        }
        if (raw == null) {
            return "";
        }
        return switch (raw.trim().toLowerCase()) {
            case "on", "ligar", "ligado", "true" -> "on";
            case "off", "desligar", "desligado", "false" -> "off";
            case "status", "estado" -> "status";
            default -> "";
        };
    }
}