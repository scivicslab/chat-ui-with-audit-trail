package com.scivicslab.chatui.core.actor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a request body into the text the I/O log keeps under {@code REQUEST:}.
 *
 * <p>The body is recorded as it was sent, except that an inline image
 * ({@code "url":"data:image/png;base64,..."}) is replaced by its length: the base64 text is the
 * largest part of a body and carries nothing a reader of the log can use.</p>
 */
final class RequestBodyRecord {

    private static final Pattern DATA_URL = Pattern.compile("\"url\"\\s*:\\s*\"data:[^\"]*\"");

    private RequestBodyRecord() {}

    /** @return the body with every inline image replaced by {@code "url":"[image N chars]"} */
    static String forLog(String requestBody) {
        Matcher m = DATA_URL.matcher(requestBody);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String whole = m.group();
            int open = whole.indexOf('"', whole.indexOf(':'));
            int chars = whole.length() - open - 2;
            m.appendReplacement(out, Matcher.quoteReplacement("\"url\":\"[image " + chars + " chars]\""));
        }
        m.appendTail(out);
        return out.toString();
    }
}
