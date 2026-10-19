package com.scivicslab.chatui.audittrail;

import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The keyboard mode of the prompt input box: {@code {"keybind": "default" | "emacs"}}. The pane
 * reads it once at start-up and, in {@code emacs} mode, binds Ctrl+a, e, f, b, n, p, d, h and k
 * in the prompt input box to the Emacs cursor and deletion commands.
 *
 * <p>An unrecognised value is answered as {@code default}, so a misspelt setting leaves the box
 * with the browser's own key bindings rather than none.</p>
 */
@Path("/api/keybind")
public class KeybindResource {

    @ConfigProperty(name = "chat-ui.keybind", defaultValue = "default")
    String keybind;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> keybind() {
        return Map.of("keybind", modeOf(keybind));
    }

    /** @return {@code emacs} when the setting says so, {@code default} for anything else */
    static String modeOf(String setting) {
        return setting != null && setting.strip().equalsIgnoreCase("emacs") ? "emacs" : "default";
    }
}
