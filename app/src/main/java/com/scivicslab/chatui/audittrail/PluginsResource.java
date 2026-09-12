package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * What this instance was started with ({@code ProviderAndToolPlugins_260912_oo01}): the loaded
 * plugin jars, the provider kinds and dropdown choices, and the tool names. The browser builds
 * its provider dropdown from {@code providerChoices}; an operator reads it to confirm which of
 * the three start-up configurations a running instance is.
 */
@Path("/api/plugins")
public class PluginsResource {

    @Inject
    ChatUiActorSystem actorSystem;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> plugins() {
        return actorSystem.getPluginRegistry().describe();
    }
}
