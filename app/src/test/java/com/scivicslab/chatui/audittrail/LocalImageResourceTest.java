package com.scivicslab.chatui.audittrail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit test for which file names {@link LocalImageResource} will serve. No HTTP, no file
 * system: the decision is made from the name alone, before anything is opened.
 *
 * <p>The list is fixed rather than probed from the bytes, so a name the list does not cover is
 * refused. That refusal is the point being held in place here: without it the endpoint would hand
 * out any readable file under whatever content type a caller could talk it into.</p>
 */
class LocalImageResourceTest {

    @Test
    void theImageTypesAreServedUnderTheirOwnContentType() {
        assertEquals("image/png", LocalImageResource.contentTypeOf("/home/devteam/works/shot.png"));
        assertEquals("image/jpeg", LocalImageResource.contentTypeOf("/tmp/a.jpg"));
        assertEquals("image/jpeg", LocalImageResource.contentTypeOf("/tmp/a.jpeg"));
        assertEquals("image/gif", LocalImageResource.contentTypeOf("/tmp/a.gif"));
        assertEquals("image/webp", LocalImageResource.contentTypeOf("/tmp/a.webp"));
        assertEquals("image/svg+xml", LocalImageResource.contentTypeOf("/tmp/a.svg"));
        assertEquals("image/bmp", LocalImageResource.contentTypeOf("/tmp/a.bmp"));
    }

    /** The extension is written both ways; the same file must be served either way. */
    @Test
    void theExtensionIsReadWithoutCase() {
        assertEquals("image/png", LocalImageResource.contentTypeOf("/home/devteam/works/SHOT.PNG"));
        assertEquals("image/jpeg", LocalImageResource.contentTypeOf("/home/devteam/works/Photo.JPG"));
    }

    /** Anything that is not one of the image names is refused before the file is opened. */
    @Test
    void anyOtherNameIsRefused() {
        assertNull(LocalImageResource.contentTypeOf("/etc/passwd"));
        assertNull(LocalImageResource.contentTypeOf("/home/devteam/works/notes.md"));
        assertNull(LocalImageResource.contentTypeOf("/home/devteam/.ssh/id_rsa"));
        assertNull(LocalImageResource.contentTypeOf("/home/devteam/works/archive.png.gz"));
        assertNull(LocalImageResource.contentTypeOf("noextension"));
        assertNull(LocalImageResource.contentTypeOf("/tmp/trailing."));
    }
}
