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
     * Renders the console shell.
     *
     * @return the rendered {@code console.html} template
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance get() {
        return console.instance();
    }
}
