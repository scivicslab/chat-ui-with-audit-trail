package com.scivicslab.chatui.agent;

import org.eclipse.microprofile.config.ConfigProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The other chat-ui instances on the same machine, as far as the cross-conversation tools
 * ({@code ask_chat}, {@code get_chat_log}, ...) are concerned.
 *
 * <p>An instance is one JVM with its own actor system. Project numbers are local to an instance, so
 * {@code project8/chat-01} names one conversation in the instance on port 28010 and another in the
 * instance on port 28014. The tools reach only the instance they run in. Asked for a name that
 * exists only elsewhere, they said "chat not found"; asked for a name that exists in both, they
 * delivered to the local one without saying so, and the sender, who had the other instance in mind,
 * never knew.</p>
 *
 * <p>The neighbours are listed in the property {@code chat-ui.peers}: ports ({@code 28010}) or base URLs
 * ({@code http://localhost:28010}), separated by commas. With the property empty, which is the default,
 * nothing here does anything and the tools answer exactly as before.</p>
 */
public final class OtherInstances {

    private OtherInstances() {}

    private static final Logger LOG = Logger.getLogger(OtherInstances.class.getName());

    private static final String PEERS_PROPERTY = "chat-ui.peers";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    /**
     * The base URLs of the other instances, from {@code chat-ui.peers}, without this instance itself.
     *
     * @return the base URLs without a trailing slash; empty when the property is not set
     */
    static List<String> peers() {
        String configured;
        Integer ownPort;
        try {
            var config = ConfigProvider.getConfig();
            configured = config.getOptionalValue(PEERS_PROPERTY, String.class).orElse("");
            ownPort = config.getOptionalValue("quarkus.http.port", Integer.class).orElse(null);
        } catch (RuntimeException e) {
            return List.of();
        }
        return parsePeers(configured, ownPort);
    }

    /**
     * @param configured the value of {@code chat-ui.peers}
     * @param ownPort    this instance's port, or {@code null} when unknown; a peer on it is left out
     * @return the base URLs of the peers
     */
    static List<String> parsePeers(String configured, Integer ownPort) {
        List<String> urls = new ArrayList<>();
        if (configured == null) return urls;
        for (String item : configured.split(",")) {
            String peer = item.strip();
            if (peer.isEmpty()) continue;
            String url = peer.matches("\\d+") ? "http://localhost:" + peer : peer.replaceAll("/+$", "");
            if (ownPort != null && url.equals("http://localhost:" + ownPort)) continue;
            if (!urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    /**
     * The peers that have a conversation of this name. A peer that does not answer in time counts as
     * not having it: this is a hint added to an answer, so it must never make the answer wait or fail.
     *
     * @param peers     base URLs from {@link #peers()}
     * @param qualified the conversation's name as {@code project8/chat-01}
     * @return the base URLs of the peers that have it, in the order of {@code peers}
     */
    static List<String> having(List<String> peers, String qualified) {
        int slash = qualified.indexOf("/chat-");
        if (slash < 0) return List.of();
        String projectId = qualified.substring(0, slash);
        String chatId = qualified.substring(slash + "/chat-".length());
        List<String> found = new ArrayList<>();
        for (String peer : peers) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(peer + "/api/projects/" + projectId + "/chats/" + chatId + "/status"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            try {
                if (HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    found.add(peer);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return found;
            } catch (Exception e) {
                LOG.log(Level.FINE, "peer " + peer + " did not answer", e);
            }
        }
        return found;
    }

    /**
     * What to add to "chat not found" when the conversation exists in another instance.
     *
     * @param qualified the conversation's name as {@code project10/chat-02}
     * @return a sentence starting with a space, or the empty string when no peer has it
     */
    public static String notFoundHint(String qualified) {
        List<String> peers = peers();
        if (peers.isEmpty()) return "";
        List<String> elsewhere = having(peers, qualified);
        if (elsewhere.isEmpty()) return "";
        return " in this instance; " + qualified + " exists in the instance at " + String.join(", ", elsewhere)
                + ", and the conversation tools reach only the instance they run in";
    }

    /**
     * What to add to the report of a delivery when another instance has a conversation of the same
     * name, because the sender may have meant that one.
     *
     * @param qualified the conversation's name as {@code project8/chat-01}
     * @return a sentence starting with a space, or the empty string when no peer has it
     */
    public static String sameNameNote(String qualified) {
        List<String> peers = peers();
        if (peers.isEmpty()) return "";
        List<String> elsewhere = having(peers, qualified);
        if (elsewhere.isEmpty()) return "";
        return " NOTE: " + qualified + " also exists in the instance at " + String.join(", ", elsewhere)
                + "; this went to the one in this instance, because the conversation tools reach only the"
                + " instance they run in";
    }
}
