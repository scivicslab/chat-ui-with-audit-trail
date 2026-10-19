package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KeybindResourceTest {

    @Test
    void emacsIsRecognisedWhateverItsCaseOrSpacing() {
        assertEquals("emacs", KeybindResource.modeOf("emacs"));
        assertEquals("emacs", KeybindResource.modeOf(" Emacs "));
    }

    @Test
    void anythingElseIsDefault() {
        assertEquals("default", KeybindResource.modeOf("default"));
        assertEquals("default", KeybindResource.modeOf("vim"));
        assertEquals("default", KeybindResource.modeOf(""));
        assertEquals("default", KeybindResource.modeOf(null));
    }
}
