package com.scivicslab.chatui.audittrail;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the static mock of the fixed two-pane console screen.
 *
 * <p>This stage renders only the visual shell (the real chat-ui markup with the
 * Hallmark audit fixes applied). There is no chat, session, or audit-trail
 * behaviour yet; the dynamic panels render in their empty state.
 */
@Path("/")
public class ConsoleResource {

    @Inject
    Template console;

    /**
     * The value appended to this application's own stylesheet and script URLs, fixed for the life
     * of the process.
     *
     * <p>Quarkus serves everything under {@code META-INF/resources} with
     * {@code cache-control: public, immutable, max-age=86400}, and a browser holding an
     * {@code immutable} response does not ask the server again — it runs yesterday's script for a
     * day, whatever the server now has. Every fix to the pane needed a hard reload, and looked to
     * whoever had not done one like a fix that had never been deployed.</p>
     *
     * <p>These files can only change when the process is replaced, so the moment this process
     * started is exactly the right value: a restart gives every asset a new URL, and nothing
     * changes underneath a running one.</p>
     */
    private final String assetVersion = String.valueOf(System.currentTimeMillis());

    /**
     * Renders the console shell.
     *
     * @return the rendered {@code console.html} template
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance get() {
        return console.data("assetVersion", assetVersion);
    }
}
