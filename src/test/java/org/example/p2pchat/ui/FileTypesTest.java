package org.example.p2pchat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTypesTest {

    @Test
    void recognisesCommonImageExtensions() {
        assertTrue(FileTypes.isImage("photo.png"));
        assertTrue(FileTypes.isImage("photo.jpg"));
        assertTrue(FileTypes.isImage("photo.jpeg"));
        assertTrue(FileTypes.isImage("anim.gif"));
        assertTrue(FileTypes.isImage("scan.bmp"));
        assertTrue(FileTypes.isImage("modern.webp"));
    }

    @Test
    void imageDetectionIsCaseInsensitive() {
        assertTrue(FileTypes.isImage("PHOTO.PNG"));
        assertTrue(FileTypes.isImage("Photo.JpEg"));
    }

    @Test
    void rejectsNonImageExtensions() {
        assertFalse(FileTypes.isImage("report.pdf"));
        assertFalse(FileTypes.isImage("archive.zip"));
        assertFalse(FileTypes.isImage("movie.mp4"));
        assertFalse(FileTypes.isImage("noextension"));
        assertFalse(FileTypes.isImage("tricky.png.exe"));
    }

    @Test
    void handlesNullAndBlankNames() {
        assertFalse(FileTypes.isImage(null));
        assertFalse(FileTypes.isImage(""));
        assertFalse(FileTypes.isImage("   "));
    }

    @Test
    void imageSizeLimitAcceptsSmallFiles() {
        assertTrue(FileTypes.canPreviewImage("a.png", 1024));
        assertTrue(FileTypes.canPreviewImage("a.png", FileTypes.MAX_PREVIEW_BYTES));
    }

    @Test
    void imageSizeLimitRejectsHugeFiles() {
        assertFalse(FileTypes.canPreviewImage("huge.png", FileTypes.MAX_PREVIEW_BYTES + 1));
        assertFalse(FileTypes.canPreviewImage("huge.png", Long.MAX_VALUE));
    }

    @Test
    void nonImageNeverGetsPreview() {
        assertFalse(FileTypes.canPreviewImage("report.pdf", 100));
    }

    @Test
    void badgeUsesUppercaseExtension() {
        assertEquals("PDF", FileTypes.badge("report.pdf"));
        assertEquals("ZIP", FileTypes.badge("archive.zip"));
        assertEquals("TXT", FileTypes.badge("notes.txt"));
    }

    @Test
    void badgeIsLimitedToFourCharacters() {
        assertEquals("DOCX", FileTypes.badge("a.docx"));
        assertEquals("MP4", FileTypes.badge("clip.mp4"));
    }

    @Test
    void badgeForDotfilesAndMissingExtension() {
        assertEquals("FILE", FileTypes.badge("noextension"));
        assertEquals("FILE", FileTypes.badge(""));
        assertEquals("FILE", FileTypes.badge(null));
    }

    @Test
    void badgeHandlesUpperExtensionInName() {
        assertEquals("PNG", FileTypes.badge("Image.PNG"));
    }

    @Test
    void isImageIgnoresDirectoryPartsForSafety() {
        assertFalse(FileTypes.isImage("folder/name"));
        assertTrue(FileTypes.isImage("C:/tmp/pic.jpg"));
    }
}
