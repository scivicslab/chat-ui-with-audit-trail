package com.scivicslab.chatui.core.service;

/**
 * Authentication mode for LLM provider access.
 */
public enum AuthMode {
    /** CLI binary is installed locally (no API key needed). */
    CLI,
    /** API key provided via environment variable or config property. */
    API_KEY,
    /** No authentication configured; must be provided via Web UI. */
    NONE
}
