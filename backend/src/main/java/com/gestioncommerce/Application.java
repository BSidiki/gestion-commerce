package com.gestioncommerce;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) throws IOException {
        prepareDataDirectory();
        SpringApplication.run(Application.class, args);
    }

    /**
     * Le dossier des données doit exister avant l'ouverture de la base.
     * Ordre de priorité : -Dapp.data-dir, variable GESTION_DATA_DIR, puis ./data.
     */
    private static void prepareDataDirectory() throws IOException {
        String configured = System.getProperty("app.data-dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("GESTION_DATA_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = "./data";
        }
        Path dataDir = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(dataDir);
        System.setProperty("app.data-dir", dataDir.toString().replace('\\', '/'));
    }
}
