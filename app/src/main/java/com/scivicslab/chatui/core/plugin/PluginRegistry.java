package com.scivicslab.chatui.core.plugin;

import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * What this instance can offer a conversation beyond its built-in tools: the provider kinds and
 * the tools, built once at start-up and never changed ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * <p>The body registers its own {@code openai-compat} factory first. Then each jar named in
 * {@code chat-ui.plugins} is put on a class loader whose parent is the body's, and the
 * {@link ChatUiPlugin} implementations that {@link ServiceLoader} finds there add their factories
 * and tools. A jar that cannot be loaded stops start-up: an instance asked for a plugin must not
 * come up silently without it.</p>
 *
 * <p>Nothing is added after start-up. Which kinds and tools exist is therefore decided by the
 * command line, which is what makes "no way out to the network" a property of the process rather
 * than of a setting.</p>
 */
public class PluginRegistry {

    private static final Logger LOG = Logger.getLogger(PluginRegistry.class.getName());

    /** One loaded plugin, as reported by {@code GET /api/plugins}. */
    public record LoadedPlugin(String id, String source, List<String> providerKinds, List<String> toolNames) {}

    private final Map<String, LlmProviderFactory> factories = new LinkedHashMap<>();
    private final Map<String, ConversationTool> tools = new LinkedHashMap<>();
    private final List<LoadedPlugin> plugins = new ArrayList<>();

    /** Registers one provider kind. A later registration of the same kind replaces the earlier. */
    public synchronized void register(LlmProviderFactory factory) {
        factories.put(factory.kind(), factory);
    }

    /** Registers one tool. A later registration of the same name replaces the earlier. */
    public synchronized void register(ConversationTool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Registers everything one plugin provides.
     *
     * @param plugin the plugin
     * @param source where it came from (a jar path, or {@code built-in})
     */
    public synchronized void register(ChatUiPlugin plugin, String source) {
        List<String> kinds = new ArrayList<>();
        for (LlmProviderFactory f : plugin.providerFactories()) {
            register(f);
            kinds.add(f.kind());
        }
        List<String> names = new ArrayList<>();
        for (ConversationTool t : plugin.tools()) {
            register(t);
            names.add(t.name());
        }
        plugins.add(new LoadedPlugin(plugin.id(), source, kinds, names));
        LOG.info("Plugin " + plugin.id() + " from " + source + ": providers " + kinds + ", tools " + names);
    }

    /**
     * Loads every plugin found in the given jars. Each jar gets its own class loader, parented to
     * this class's, so a plugin sees the body's {@code LlmProvider} and never a copy of it.
     *
     * @param jars paths of plugin jars
     * @throws IllegalStateException when a jar is missing, unreadable, or holds no plugin
     */
    public synchronized void loadJars(List<Path> jars) {
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("Plugin jar not found: " + jar);
            }
            URL url;
            try {
                url = jar.toUri().toURL();
            } catch (Exception e) {
                throw new IllegalStateException("Plugin jar path is not a URL: " + jar, e);
            }
            URLClassLoader loader = new URLClassLoader(new URL[] {url}, PluginRegistry.class.getClassLoader());
            int found = 0;
            for (ChatUiPlugin plugin : ServiceLoader.load(ChatUiPlugin.class, loader)) {
                register(plugin, jar.toString());
                found++;
            }
            if (found == 0) {
                throw new IllegalStateException("No ChatUiPlugin service in " + jar
                        + " (META-INF/services/" + ChatUiPlugin.class.getName() + " missing?)");
            }
        }
        if (jars.isEmpty()) LOG.info("Plugins: none (chat-ui.plugins is unset)");
    }

    /** @return the factory of the given kind, if registered */
    public synchronized Optional<LlmProviderFactory> factory(String kind) {
        return Optional.ofNullable(factories.get(kind));
    }

    /** @return the registered kinds, in registration order */
    public synchronized List<String> kinds() {
        return List.copyOf(factories.keySet());
    }

    /** @return every dropdown entry, in registration order of kinds */
    public synchronized List<ProviderChoice> choices() {
        List<ProviderChoice> all = new ArrayList<>();
        for (LlmProviderFactory f : factories.values()) all.addAll(f.choices());
        return Collections.unmodifiableList(all);
    }

    /** @return the registered tools, in registration order */
    public synchronized List<ConversationTool> tools() {
        return List.copyOf(tools.values());
    }

    /** @return the loaded plugins, in load order */
    public synchronized List<LoadedPlugin> plugins() {
        return List.copyOf(plugins);
    }

    /** @return what {@code GET /api/plugins} returns */
    public synchronized Map<String, Object> describe() {
        List<Map<String, Object>> choiceRows = new ArrayList<>();
        for (LlmProviderFactory f : factories.values()) {
            for (ProviderChoice c : f.choices()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", c.kind());
                row.put("toolSet", c.toolSet().id());
                row.put("label", c.label());
                row.put("value", c.value(f.defaultToolSet()));
                choiceRows.add(row);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plugins", plugins());
        m.put("providerKinds", kinds());
        m.put("providerChoices", choiceRows);
        m.put("tools", tools.keySet().stream().toList());
        return m;
    }
}
