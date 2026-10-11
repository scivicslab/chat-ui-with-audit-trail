package com.scivicslab.chatui.audittrail;

import java.util.List;
import java.util.Map;

import com.scivicslab.chatui.translation.TranslationLanguages;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The languages a message can be translated into, for the Translate list under each message
 * ({@code MessageTranslation_261011_oo01}): {@code [{code, name}, ...]}, English first.
 */
@Path("/api/translation-languages")
public class TranslationLanguagesResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> languages() {
        return TranslationLanguages.ALL.stream()
                .map(l -> Map.of("code", l.code(), "name", l.name()))
                .toList();
    }
}
