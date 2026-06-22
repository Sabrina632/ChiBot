package org.chibot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.chibot.Commands.Admin.MaintenanceCommand;
import org.chibot.Commands.CommandListener;
import org.chibot.Commands.CommandManager;
import org.chibot.Commands.Core.ChiActivity;
import org.chibot.Commands.Core.HelpButtons;
import org.chibot.Config.ChiConfig;
import org.chibot.Database.LanguageRepository;
import org.chibot.Database.MaintenanceRepository;
import org.chibot.Translation.AwsTranslator;
import org.chibot.Translation.ResilientTranslator;
import org.chibot.Translation.TranslationService;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ChiBot
{

    private static final Logger log = LoggerFactory.getLogger(ChiBot.class);

    private final ChiConfig config;
    private JDA jda;

    public ChiBot(ChiConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        CommandManager commandManager = new CommandManager();

        // Estado global do bot (banco separado): restaura o modo manutenção antes
        // do JDA subir, pro bot voltar pausado se foi assim que ele desligou.
        MaintenanceCommand.init(new MaintenanceRepository());

        // Tradução por usuário (banco ChiLang.db + Amazon Translate). Sem credencial
        // AWS no .env, o tradutor é null e tudo fica em português. O ResilientTranslator
        // blinda contra falha de rede: degrada pro pt em silêncio em vez de travar.
        TranslationService.init(new LanguageRepository(),
                ResilientTranslator.wrap(AwsTranslator.fromConfig(config)));

        // Musica via Lavalink: o servico precisa existir antes do JDA por causa
        // do interceptor de voz (e dos comandos, que o acessam como singleton).
        MusicService musicService = MusicService.init(config);

        // Sistema de waifu/husbando: o servico escuta os botoes de claim/kakera.
        HaremService haremService = HaremService.init();

        jda = JDABuilder.createDefault(config.getToken())
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGE_REACTIONS)
                .setVoiceDispatchInterceptor(musicService.getVoiceInterceptor())
                .addEventListeners(new CommandListener(config, commandManager), haremService,
                        new HelpButtons())
                .build()
                .awaitReady();

        // Agora que o JDA esta pronto, a musica pode reconectar nos canais e
        // retomar as filas que sobreviveram ao ultimo restart.
        musicService.setJda(jda);

        registerSlashCommands(commandManager);

        // Sobe/carrega os emojis customizados do harem (kakera colorido estilo Mudae).
        HaremEmojis.sync(jda);

        // Atividade dinâmica: "!help | <usuários>", atualizada de tempos em tempos.
        new ChiActivity(jda, config.getPrefix()).start();

        log.info("ChiBot conectado como {}", jda.getSelfUser().getName());

        logOwner();
    }

    /** Confirma no console qual usuário foi reconhecido como dono (OWNER_ID do .env). */
    private void logOwner() {
        String ownerId = config.getOwnerId();
        if (ownerId == null || ownerId.isBlank()) {
            log.warn("OWNER_ID não configurado no .env — comandos do dono ficam indisponíveis.");
            return;
        }
        jda.retrieveUserById(ownerId).queue(
                owner -> log.info("Dono reconhecido: {} (ID {})", owner.getName(), ownerId),
                err -> log.warn("OWNER_ID {} configurado, mas não achei esse usuário no Discord.", ownerId));
    }

    private void registerSlashCommands(CommandManager commandManager) {
        List<SlashCommandData> slashCommands = commandManager.buildSlashCommands();
        if (slashCommands.isEmpty()) {
            return;
        }

        // Registro global: os comandos ficam disponíveis em qualquer servidor e
        // aparecem na seção "Comandos" do perfil do bot (igual o Mudae). Pode
        // levar até ~1h pra propagar no Discord.
        jda.updateCommands().addCommands(slashCommands).queue(
                ok -> log.info("{} slash command(s) registrado(s) globalmente (pode demorar pra aparecer).",
                        slashCommands.size()),
                err -> log.error("Falha ao registrar slash commands globais.", err));

        // Limpa registros antigos presos a um servidor específico. Sem isso, os
        // comandos guild + global apareceriam duplicados naquele servidor.
        String guildId = config.getGuildId();
        if (guildId != null && !guildId.isBlank()) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().queue(
                        ok -> log.info("Comandos antigos do servidor {} limpos (agora tudo é global).",
                                guild.getName()),
                        err -> log.warn("Não consegui limpar os comandos antigos do servidor.", err));
            }
        }
    }

    public JDA getJda() {
        return jda;
    }

    public ChiConfig getConfig() {
        return config;
    }
}