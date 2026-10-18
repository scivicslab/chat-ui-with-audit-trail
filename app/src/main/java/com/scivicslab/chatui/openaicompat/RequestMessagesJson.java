package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code messages} array of a request body, as a JSON text a caller can read and write
 * ({@code RequestMessagesControl_261018_oo01}).
 *
 * <p>An element is {@code {"role": ..., "content": ...}}. {@code role} is {@code system},
 * {@code user} or {@code assistant}. {@code content} is a string, or an array of
 * {@code {"type":"text","text":...}} and {@code {"type":"image_url","image_url":{"url":...}}}
 * parts; only a user message may carry images. The {@code tool} role is not accepted, because the
 * agent loop of this product reads tool calls from the reply's text.</p>
 */
public final class RequestMessagesJson {

    private RequestMessagesJson() {}

    /** @return the messages as a JSON array text; an image is written as an {@code image_url} part */
    public static String toJson(List<ChatMessage> messages) {
        JSONArray array = new JSONArray();
        for (ChatMessage m : messages) {
            JSONObject o = new JSONObject().put("role", m.role());
            if (m instanceof ChatMessage.User u && u.hasImages()) {
                JSONArray parts = new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", u.content()));
                for (String url : u.imageDataUrls()) {
                    parts.put(new JSONObject().put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", url)));
                }
                o.put("content", parts);
            } else {
                o.put("content", contentOf(m));
            }
            array.put(o);
        }
        return array.toString();
    }

    /**
     * @return the messages the text describes
     * @throws IllegalArgumentException when the text is not a JSON array of acceptable messages;
     *                                  the message says which element is wrong
     */
    public static List<ChatMessage> parse(String json) {
        JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (JSONException | NullPointerException e) {
            throw new IllegalArgumentException("not a JSON array of messages");
        }
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException("element " + i + " is not an object");
            String role = o.optString("role", "");
            Object content = o.opt("content");
            switch (role) {
                case "system" -> out.add(new ChatMessage.System(textOf(content, i, false).text()));
                case "assistant" -> out.add(new ChatMessage.Assistant(textOf(content, i, false).text()));
                case "user" -> {
                    Parsed p = textOf(content, i, true);
                    out.add(new ChatMessage.User(p.text(), p.images()));
                }
                default -> throw new IllegalArgumentException("element " + i + ": role must be"
                        + " system, user or assistant, not \"" + role + "\"");
            }
        }
        return out;
    }

    private record Parsed(String text, List<String> images) {}

    private static Parsed textOf(Object content, int index, boolean imagesAllowed) {
        if (content instanceof String s) return new Parsed(s, List.of());
        if (!(content instanceof JSONArray parts)) {
            throw new IllegalArgumentException("element " + index + ": content must be a string or an array");
        }
        StringBuilder text = new StringBuilder();
        List<String> images = new ArrayList<>();
        for (int j = 0; j < parts.length(); j++) {
            JSONObject part = parts.optJSONObject(j);
            String type = part == null ? "" : part.optString("type", "");
            if (type.equals("text")) {
                text.append(part.optString("text", ""));
            } else if (type.equals("image_url") && imagesAllowed) {
                JSONObject image = part.optJSONObject("image_url");
                String url = image == null ? "" : image.optString("url", "");
                if (url.isEmpty()) throw new IllegalArgumentException("element " + index + ": image_url has no url");
                images.add(url);
            } else {
                throw new IllegalArgumentException("element " + index + ": content part " + j
                        + " must be text" + (imagesAllowed ? " or image_url" : ""));
            }
        }
        return new Parsed(text.toString(), images);
    }

    private static String contentOf(ChatMessage m) {
        return switch (m) {
            case ChatMessage.User u -> u.content();
            case ChatMessage.Assistant a -> a.content();
            case ChatMessage.System s -> s.content();
            case ChatMessage.ToolResult t -> t.content();
            case ChatMessage.ToolCallRequest t -> "";
        };
    }
}
