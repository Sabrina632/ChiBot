package org.chibot.Commands.Core;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Mantém a atividade do bot ("jogando ...") mostrando o comando de ajuda e a
 * quantidade de usuários atendidos. Ex.: "!help | 1234 usuários".
 *
 * A contagem muda conforme gente entra/sai, então atualizamos de tempos em
 * tempos numa thread daemon em vez de só na inicialização.
 */
public class ChiActivity {

    /** De quanto em quanto tempo a atividade é recalculada. */
    private static final long REFRESH_MINUTES = 5;

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
        scheduler.scheduleAtFixedRate(this::update, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    private void update() {
        long usuarios = jda.getGuilds().stream()
                .mapToLong(Guild::getMemberCount)
                .sum();
        jda.getPresence().setActivity(Activity.playing(
                prefix + "help | " + usuarios + " usuários"));
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "chi-activity");
            thread.setDaemon(true);
            return thread;
        };
    }
}