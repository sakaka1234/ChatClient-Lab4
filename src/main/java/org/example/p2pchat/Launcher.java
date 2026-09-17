package org.example.p2pchat;

import javafx.application.Application;

/**
 * Entry point that does NOT extend {@link Application}.
 *
 * <p>When the JVM main class is a subclass of {@code javafx.application.Application} and JavaFX
 * lives on the classpath (plain {@code java -cp ...}, or a fat jar) instead of the module path,
 * the launcher aborts with {@code Error: JavaFX runtime components are missing, and are required to
 * run this application}. Starting JavaFX from a plain class avoids that check.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }
}
