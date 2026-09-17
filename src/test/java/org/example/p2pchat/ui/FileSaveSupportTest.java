package org.example.p2pchat.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSaveSupportTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void copiesSourceToChosenDestination(@TempDir Path dir) throws IOException {
        Path received = Files.createDirectory(dir.resolve("received"));
        Path source = Files.write(received.resolve("report.pdf"), bytes("pdf content"));
        Path target = dir.resolve("Desktop/report.pdf");

        FileSaveSupport.saveAs(source, target);

        assertArrayEquals(bytes("pdf content"), Files.readAllBytes(target));
    }

    @Test
    void createsParentDirectoriesOfDestination(@TempDir Path dir) throws IOException {
        Path source = Files.write(dir.resolve("a.txt"), bytes("hello"));
        Path target = dir.resolve("deep/nested/folder/a.txt");

        FileSaveSupport.saveAs(source, target);

        assertTrue(Files.exists(target));
    }

    @Test
    void refusedWhenSourceDoesNotExist(@TempDir Path dir) {
        Path missing = dir.resolve("missing.bin");

        assertThrows(IOException.class, () -> FileSaveSupport.saveAs(missing, dir.resolve("out.bin")));
    }

    @Test
    void refusesWhenDestinationEqualsSource(@TempDir Path dir) throws IOException {
        Path source = Files.write(dir.resolve("same.txt"), bytes("data"));

        assertThrows(IOException.class, () -> FileSaveSupport.saveAs(source, source));
    }

    @Test
    void overwritesExistingDestination(@TempDir Path dir) throws IOException {
        Path source = Files.write(dir.resolve("new.txt"), bytes("new content"));
        Path target = Files.write(dir.resolve("old.txt"), bytes("old content"));

        FileSaveSupport.saveAs(source, target);

        assertArrayEquals(bytes("new content"), Files.readAllBytes(target));
    }

    @Test
    void suggestedNameUsesFileNameWhenPresent(@TempDir Path dir) {
        assertEquals("fallback.bin", FileSaveSupport.suggestedName(null, "fallback.bin"));
        assertEquals("real.txt", FileSaveSupport.suggestedName(dir.resolve("real.txt"), "fallback.bin"));
        assertEquals("fallback.bin", FileSaveSupport.suggestedName(dir.getRoot(), "fallback.bin"));
    }

    @Test
    void isUnderReceivedFolderDetectsContainment(@TempDir Path dir) {
        Path received = dir.resolve("received");
        Path inside = received.resolve("a.txt");
        Path outside = dir.resolve("elsewhere/a.txt");

        assertTrue(FileSaveSupport.isInside(received, inside));
        assertFalse(FileSaveSupport.isInside(received, outside));
        assertFalse(FileSaveSupport.isInside(received, received));
    }
}
