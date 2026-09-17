package org.example.p2pchat.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileSaveSupport {

    private FileSaveSupport() {
    }

    public static void saveAs(Path source, Path destination) throws IOException {
        if (source == null || destination == null) {
            throw new IOException("Source and destination are required");
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("Source file does not exist: " + source);
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedDestination)) {
            throw new IOException("Source and destination are the same file");
        }
        Path parent = normalizedDestination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String suggestedName(Path path, String fallback) {
        if (path == null || path.getFileName() == null) {
            return fallback;
        }
        return path.getFileName().toString();
    }

    public static boolean isInside(Path folder, Path candidate) {
        if (folder == null || candidate == null) {
            return false;
        }
        Path root = folder.toAbsolutePath().normalize();
        Path target = candidate.toAbsolutePath().normalize();
        return !target.equals(root) && target.startsWith(root);
    }
}
