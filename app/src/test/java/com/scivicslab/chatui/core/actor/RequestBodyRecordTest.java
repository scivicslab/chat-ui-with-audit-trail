package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RequestBodyRecordTest {

    @Test
    void bodyWithoutImagesIsKeptAsItWas() {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        assertEquals(body, RequestBodyRecord.forLog(body));
    }

    @Test
    void inlineImageIsReplacedByItsLength() {
        String data = "data:image/png;base64,AAAA";
        String body = "{\"image_url\":{\"url\":\"" + data + "\"}}";
        assertEquals("{\"image_url\":{\"url\":\"[image " + data.length() + " chars]\"}}",
                RequestBodyRecord.forLog(body));
    }

    @Test
    void everyImageInTheBodyIsReplaced() {
        String body = "[{\"url\":\"data:a\"},{\"url\":\"data:bb\"}]";
        assertEquals("[{\"url\":\"[image 6 chars]\"},{\"url\":\"[image 7 chars]\"}]",
                RequestBodyRecord.forLog(body));
    }
}
