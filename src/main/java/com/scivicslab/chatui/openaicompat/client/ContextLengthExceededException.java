package com.scivicslab.chatui.openaicompat.client;

/**
 * Thrown when the prompt exceeds the model's maximum context length.
 */
public class ContextLengthExceededException extends RuntimeException {
    /**
     * Creates a new exception indicating that the context length was exceeded.
     *
     * @param message detail message describing the overflow (typically the server error body)
     */
    public ContextLengthExceededException(String message) { super(message); }
}
