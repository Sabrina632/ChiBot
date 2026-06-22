package org.chibot.Commands.Core;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateOnlineStatusEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mantém a atividade do bot ("jogando ...") em tempo real, mostrando o comando
 * de ajuda e a quantidade de usuários online (sem contar bots).
 * Ex.: "!help | 42 online".
 *
 * Em vez de varrer num intervalo fixo, reage aos eventos do Discord (alguém
 * ficou online/offline, entrou ou saiu) e recalcula na hora. Pra não estourar
 * o rate limit de presença (5 updates a cada 20s), as rajadas de eventos são
 * agrupadas num único update com um espaçamento mínimo ({@link #MIN_INTERVAL_MS}).
 *
 * Requer os intents GUILD_MEMBERS e GUILD_PRESENCES (e o cache ONLINE_STATUS)
 * ligados no JDA — senão o status dos membros vem sempre como offline.
 */
public class ChiActivity extends ListenerAdapter {

    /** Espaçamento mínimo entre updates de presença, pra respeitar o rate limit. */
    private static final long MIN_INTERVAL_MS = 5_000;

    private final JDA jda;
    private final String prefix;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(daemonFactory());
    /** Garante um único update agendado por vez (coalesce as rajadas de evento). */
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private volatile long lastRun = 0;

    public ChiActivity(JDA jda, String prefix) {
        this.jda = jda;
        this.prefix = prefix;
    }

    /** Aplica a atividade uma vez na inicialização. */
    public void start() {
        scheduleUpdate();
    }

    @Override
    public void onUserUpdateOnlineStatus(UserUpdateOnlineStatusEvent event) {
        scheduleUpdate();
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        scheduleUpdate();
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        scheduleUpdate();
    }

    /**
     * Agenda um recálculo. Se já tem um a caminho, ignora (ele vai pegar o estado
     * mais novo). Senão, agenda respeitando o espaçamento mínimo desde o último.
     */
    private void scheduleUpdate() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        long since = System.currentTimeMillis() - lastRun;
        long delay = Math.max(0, MIN_INTERVAL_MS - since);
        scheduler.schedule(this::update, delay, TimeUnit.MILLISECONDS);
    }

    private void update() {
        pending.set(false);
        lastRun = System.currentTimeMillis();
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