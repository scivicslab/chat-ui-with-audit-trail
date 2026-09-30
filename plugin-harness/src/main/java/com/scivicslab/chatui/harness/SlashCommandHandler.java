package com.scivicslab.chatui.harness;

import java.util.List;
import java.util.function.Consumer;

import com.scivicslab.chatui.core.rest.ChatEvent;

/**
 * The commands a person types into the prompt box that this program answers itself, rather than
 * sending to the CLI ({@code SlashCommandsAndClearButton_260929_oo01}).
 *
 * <p>The CLI's own slash commands belong to its interactive screen. Started with
 * {@code --output-format stream-json}, it has no such screen, and a line beginning with a slash
 * reaches the model as a question to answer in prose. What these commands change is the
 * {@link CliConfig} the next {@code claude} invocation is built from.</p>
 *
 * <p>Ported from {@code quarkus-chat-ui}'s handler of the same name, which is where this set of
 * five commands comes from.</p>
 */
public class SlashCommandHandler {

    /** The effort levels the CLI accepts, so a typo is refused before it reaches the command line. */
    private static final List<String> EFFORT_LEVELS =
            List.of("low", "medium", "high", "xhigh", "max");

    private final CliProcess cliProcess;

    /**
     * @param cliProcess the process whose {@link CliConfig} these commands change
     */
    public SlashCommandHandler(CliProcess cliProcess) {
        this.cliProcess = cliProcess;
    }

    /**
     * @param input the raw text a person typed
     * @return whether this program answers it instead of the CLI
     */
    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    /**
     * Carries out one command and hands what the person should see to {@code sender}.
     *
     * @param input  the whole line, leading slash included
     * @param sender told one event per thing to show
     */
    public void handle(String input, Consumer<ChatEvent> sender) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/model" -> handleModel(args, sender);
            case "/effort" -> handleEffort(args, sender);
            case "/clear" -> handleClear(sender);
            case "/session" -> handleSession(args, sender);
            case "/help", "/?" -> handleHelp(sender);
            default -> sender.accept(ChatEvent.error(
                    "Unknown command: " + command + " (type /help for available commands)"));
        }
    }

    private void handleModel(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            sender.accept(ChatEvent.info("Current model: " + cliProcess.getConfig().model()));
            return;
        }
        cliProcess.setConfig(cliProcess.getConfig().withModel(args));
        sender.accept(ChatEvent.info("Model changed to: " + args));
    }

    /**
     * Shows or changes how deeply the model thinks.
     *
     * <p>An unset level is reported as the CLI's own default rather than as a value this program
     * chose, because that is what an unset level means: no {@code --effort} on the command line.</p>
     */
    private void handleEffort(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            String current = cliProcess.getConfig().effort();
            sender.accept(ChatEvent.info("Current effort: "
                    + (current == null ? "unset (the CLI's own default)" : current)));
            return;
        }
        String level = args.toLowerCase();
        if (!EFFORT_LEVELS.contains(level)) {
            sender.accept(ChatEvent.error("Unknown effort level: " + args
                    + " (choose one of " + String.join(", ", EFFORT_LEVELS) + ")"));
            return;
        }
        cliProcess.setConfig(cliProcess.getConfig().withEffort(level));
        sender.accept(ChatEvent.info("Effort changed to: " + level));
    }

    private void handleClear(Consumer<ChatEvent> sender) {
        cliProcess.cancel();
        cliProcess.setConfig(cliProcess.getConfig().withSessionId(null));
        cliProcess.clearLastSessionId();
        sender.accept(ChatEvent.info("Session cleared. Starting fresh conversation."));
    }

    private void handleSession(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            String sessionId = cliProcess.getLastSessionId();
            sender.accept(ChatEvent.info(sessionId != null
                    ? "Current session: " + sessionId : "No active session."));
            return;
        }
        cliProcess.setConfig(cliProcess.getConfig().withSessionId(args));
        sender.accept(ChatEvent.info("Session set to: " + args));
    }

    private void handleHelp(Consumer<ChatEvent> sender) {
        sender.accept(ChatEvent.info("""
            Available commands:
              /help, /?          Show this help
              /model [name]      Show or change the model
              /effort [level]    Show or change the effort level
                                 (low, medium, high, xhigh, max)
              /session [id]      Show or set session ID
              /clear             Clear session (start fresh)"""));
    }
}
