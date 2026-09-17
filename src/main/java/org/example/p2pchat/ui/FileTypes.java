package org.example.p2pchat.ui;

import java.util.Locale;
import java.util.Set;

public final class FileTypes {

    public static final long MAX_PREVIEW_BYTES = 8L * 1024 * 1024;
    public static final String DEFAULT_BADGE = "FILE";
    public static final int MAX_BADGE_LENGTH = 4;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private FileTypes() {
    }

    public static boolean isImage(String fileName) {
        String extension = extensionOf(fileName);
        return !extension.isEmpty()
                && extension.indexOf('/') < 0
                && extension.indexOf('\\') < 0
                && IMAGE_EXTENSIONS.contains(extension);
    }

    public static boolean canPreviewImage(String fileName, long fileSizeBytes) {
        return isImage(fileName) && fileSizeBytes >= 0 && fileSizeBytes <= MAX_PREVIEW_BYTES;
    }

    public static String badge(String fileName) {
        String extension = extensionOf(fileName);
        if (extension.isEmpty()) {
            return DEFAULT_BADGE;
        }
        String upper = extension.toUpperCase(Locale.ROOT);
        return upper.length() > MAX_BADGE_LENGTH ? upper.substring(0, MAX_BADGE_LENGTH) : upper;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.strip();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot < separator || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
