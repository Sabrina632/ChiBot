package org.chibot.Commands;

import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class CommandManager {

    private static final Logger log = LoggerFactory.getLogger(CommandManager.class);
    private static final String COMMANDS_PACKAGE = "org.chibot.Commands";

    private static volatile CommandManager instance;

    // LinkedHashMap: mantem a ordem de registro, pro help e o registro de slash
    // commands sairem sempre na mesma ordem entre boots.
    private final Map<String, ICommand> commands = new LinkedHashMap<>();

    public CommandManager() {
        autoLoad(COMMANDS_PACKAGE);
        instance = this;
    }

    /** Instancia unica criada no boot (pro help conseguir listar os comandos). */
    public static CommandManager get() {
        return instance;
    }

    public void register(ICommand command) {
        commands.put(command.getName().toLowerCase(), command);
        for (String alias : command.getAliases()) {
            commands.put(alias.toLowerCase(), command);
        }
    }

    public ICommand get(String name) {
        return commands.get(name.toLowerCase());
    }

    /** Comandos unicos registrados (sem duplicar por causa dos aliases). */
    public Collection<ICommand> getCommands() {
        return new LinkedHashSet<>(commands.values());
    }

    /** Monta os dados de slash command de todos os comandos que aceitam slash. */
    public List<SlashCommandData> buildSlashCommands() {
        List<SlashCommandData> slashCommands = new ArrayList<>();
        for (ICommand command : getCommands()) {
            if (!command.isSlashEnabled()) {
                continue;
            }
            SlashCommandData data = Commands.slash(
                    command.getName().toLowerCase(),
                    trimDescription(command.getDescription()));
            if (!command.getOptions().isEmpty()) {
                data.addOptions(command.getOptions());
            }
            slashCommands.add(data);
        }
        return slashCommands;
    }

    /** A descricao do slash precisa ter entre 1 e 100 caracteres. */
    private static String trimDescription(String description) {
        if (description == null || description.isBlank()) {
            return "Sem descricao.";
        }
        if (description.length() <= 100) {
            return description;
        }
        // Nao corta no meio de um surrogate pair (emoji), senao a string fica
        // malformada e o Discord rejeita o registro do comando.
        int cut = Character.isHighSurrogate(description.charAt(99)) ? 99 : 100;
        return description.substring(0, cut);
    }

    private void autoLoad(String packageName) {
        try {
            List<Class<?>> classes = findClasses(packageName);
            for (Class<?> clazz : classes) {
                tryRegister(clazz);
            }
            log.info("{} comando(s) carregado(s) automaticamente.", getCommands().size());
        } catch (Exception e) {
            log.error("Falha ao carregar comandos do pacote {}", packageName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void tryRegister(Class<?> clazz) {
        boolean isCommand = ICommand.class.isAssignableFrom(clazz)
                && !clazz.isInterface()
                && !Modifier.isAbstract(clazz.getModifiers());
        if (!isCommand) {
            return;
        }
        try {
            ICommand command = ((Class<? extends ICommand>) clazz)
                    .getDeclaredConstructor()
                    .newInstance();
            register(command);
            log.info("Comando carregado: {}", command.getName());
        } catch (ReflectiveOperationException e) {
            log.error("Falha ao instanciar o comando {} (precisa de construtor sem argumentos)",
                    clazz.getName(), e);
        }
    }

    private List<Class<?>> findClasses(String packageName) throws IOException {
        String path = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<Class<?>> classes = new ArrayList<>();

        Enumeration<URL> resources = classLoader.getResources(path);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            switch (resource.getProtocol()) {
                case "file" -> {
                    try {
                        classes.addAll(scanDirectory(Paths.get(resource.toURI()), packageName));
                    } catch (Exception e) {
                        log.warn("Nao foi possivel ler o diretorio {}", resource, e);
                    }
                }
                case "jar" -> classes.addAll(scanJar(resource, path));
                default -> log.debug("Protocolo de classpath ignorado: {}", resource.getProtocol());
            }
        }
        return classes;
    }

    private List<Class<?>> scanDirectory(Path dir, String packageName) throws IOException {
        List<Class<?>> classes = new ArrayList<>();
        if (!Files.exists(dir)) {
            return classes;
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.collect(Collectors.toList());
        }
        for (Path entry : entries) {
            String fileName = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                classes.addAll(scanDirectory(entry, packageName + "." + fileName));
            } else if (fileName.endsWith(".class")) {
                String className = packageName + "." + fileName.substring(0, fileName.length() - 6);
                loadClass(className).ifPresent(classes::add);
            }
        }
        return classes;
    }

    private List<Class<?>> scanJar(URL resource, String path) throws IOException {
        List<Class<?>> classes = new ArrayList<>();
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(path) && name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    loadClass(className).ifPresent(classes::add);
                }
            }
        }
        return classes;
    }

    private java.util.Optional<Class<?>> loadClass(String className) {
        try {
            return java.util.Optional.of(Class.forName(className));
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            log.warn("Nao foi possivel carregar a classe {}", className);
            return java.util.Optional.empty();
        }
    }
}