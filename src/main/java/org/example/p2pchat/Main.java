package org.example.p2pchat;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.example.p2pchat.ui.MainController;
import org.example.p2pchat.util.AppLogger;

public class Main extends Application {

    private static final String TITLE = "P2P Chat";

    @Override
    public void start(Stage stage) {
        MainController controller = new MainController(stage);
        Scene scene = new Scene(controller.root(), 980, 660);
        scene.getStylesheets().add(
                Main.class.getResource("/org/example/p2pchat/ui/app.css").toExternalForm());

        stage.setOnCloseRequest(event -> controller.shutdown());
        stage.setTitle(TITLE);
        stage.setMinWidth(760);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
        AppLogger.info("Application started");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
