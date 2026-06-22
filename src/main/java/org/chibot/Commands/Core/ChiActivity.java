package org.chibot.Commands.Core;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Mantém a atividade do bot ("jogando ...") mostrando o comando de ajuda e a
 * quantidade de usuários online (sem contar bots). Ex.: "!help | 42 online".
 *
 * A contagem muda o tempo todo conforme gente fica online/offline, então
 * atualizamos a cada 10s numa thread daemon.
 *
 * Requer os intents GUILD_MEMBERS e GUILD_PRESENCES (e o cache ONLINE_STATUS)
 * ligados no JDA — senão o status dos membros vem sempre como offline.
 */
public class ChiActivity {

    /** De quantos em quantos segundos a atividade é recalculada. */
    private static final long REFRESH_SECONDS = 10;

    private final JDA jda;
    private final String prefix;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(daemonFactory());

    public ChiActivity(JDA jda, String prefix) {
        this.jda = jda;
        this.prefix = prefix;
    }

    /** Aplica a atividade na hora e agenda as atualizações periódicas. */
    public void start() {
        scheduler.scheduleAtFixedRate(this::update, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    private void update() {
        // Conta membros online (qualquer coisa que não seja offline) e que não
        // sejam bots. distinct() evita contar duas vezes quem está em mais de
        // um servidor onde a Chi também está.
        long online = jda.getGuilds().stream()
                .flatMap(guild -> guild.getMembers().stream())
                .filter(member -> !member.getUser().isBot())
                .filter(member -> member.getOnlineStatus() != OnlineStatus.OFFLINE)
                .mapToLong(member -> member.getIdLong())
                .distinct()
                .count();
        jda.getPresence().setActivity(Activity.playing(
                prefix + "help | " + online + " online"));
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "chi-activity");
            thread.setDaemon(true);
            return thread;
        };
    }
}