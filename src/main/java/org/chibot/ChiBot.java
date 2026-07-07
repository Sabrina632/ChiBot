package org.chibot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.chibot.Commands.Admin.MaintenanceCommand;
import org.chibot.Commands.CommandListener;
import org.chibot.Commands.CommandManager;
import org.chibot.Commands.Core.ChiActivity;
import org.chibot.Commands.Core.HelpButtons;
import org.chibot.Config.ChiConfig;
import org.chibot.Database.Db;
import org.chibot.Database.LanguageRepository;
import org.chibot.Database.MaintenanceRepository;
import org.chibot.Database.SqliteToPostgresMigration;
import org.chibot.Translation.DeepLTranslator;
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

        // Primeira subida com PostgreSQL: importa os dados dos bancos SQLite
        // legados (se existirem) antes de qualquer serviço tocar no banco.
        // Nas subidas seguintes o marcador faz isso custar um SELECT.
        SqliteToPostgresMigration.run(Db.dataSource());

        // Estado global do bot (banco separado): restaura o modo manutenção antes
        // do JDA subir, pro bot voltar pausado se foi assim que ele desligou.
        MaintenanceCommand.init(new MaintenanceRepository());

        // Tradução por usuário (banco ChiLang.db + DeepL). Sem a DEEPL_API_KEY no
        // .env, o tradutor é null e tudo fica em português. O ResilientTranslator
        // blinda contra falha de rede: degrada pro pt em silêncio em vez de travar.
        TranslationService.init(new LanguageRepository(),
                ResilientTranslator.wrap(DeepLTranslator.fromConfig(config)));

        // Musica via Lavalink: o servico precisa existir antes do JDA por causa
        // do interceptor de voz (e dos comandos, que o acessam como singleton).
        MusicService musicService = MusicService.init(config);

        // Sistema de waifu/husbando: o servico escuta os botoes de claim/kakera.
        HaremService haremService = HaremService.init();

        jda = JDABuilder.createDefault(config.getToken())
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGE_REACTIONS,
                        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES)
                // Cacheia todos os membros + status online pra contagem da atividade
                // (!help | N online). Exige os intents privilegiados acima ligados
                // também no Developer Portal, senão o login falha.
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .enableCache(CacheFlag.ONLINE_STATUS)
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

        // Atividade em tempo real: "!help | <online>", recalculada quando alguém
        // fica online/offline, entra ou sai (a própria ChiActivity escuta esses eventos).
        ChiActivity chiActivity = new ChiActivity(jda, config.getPrefix());
        jda.addEventListener(chiActivity);
        chiActivity.start();

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