package com.gestioncommerce;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    /** Nom renvoyé par /api/health : permet de reconnaître une instance déjà lancée. */
    public static final String APP_NAME = "gestion-commerce";

    private static final String NO_BROWSER = "--no-browser";

    /**
     * Version installée (lancée par GestionCommerce.exe, avec -Dapp.packaged=true) :
     * le logiciel ne se lance qu'une fois, range ses données dans le dossier de
     * l'utilisateur Windows et ouvre le navigateur sur la caisse.
     * En développement (mvn spring-boot:run), rien de tout cela : données dans ./data.
     */
    public static void main(String[] args) throws IOException {
        boolean packaged = Boolean.getBoolean("app.packaged");
        boolean openBrowser = packaged && !Arrays.asList(args).contains(NO_BROWSER);
        String url = "http://127.0.0.1:" + Integer.getInteger("server.port", 8080) + "/";

        if (packaged && alreadyRunning(url)) {
            // Second clic sur le raccourci : le logiciel tourne déjà, on montre simplement la caisse.
            if (openBrowser) {
                openInBrowser(url);
            }
            return;
        }

        prepareDataDirectory(packaged);
        SpringApplication.run(Application.class, args);
        if (openBrowser) {
            openInBrowser(url);
        }
    }

    /**
     * Le dossier des données doit exister avant l'ouverture de la base.
     * Ordre de priorité : -Dapp.data-dir, variable GESTION_DATA_DIR, dossier de
     * l'utilisateur pour la version installée, puis ./data.
     */
    private static void prepareDataDirectory(boolean packaged) throws IOException {
        String configured = System.getProperty("app.data-dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("GESTION_DATA_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = packaged ? installedDataDirectory() : "./data";
        }
        Path dataDir = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(dataDir);
        System.setProperty("app.data-dir", dataDir.toString().replace('\\', '/'));

        if (packaged && System.getProperty("logging.file.name") == null) {
            // Sans console, le journal est la seule trace en cas de problème.
            Path logs = dataDir.resolve("journaux");
            Files.createDirectories(logs);
            System.setProperty("logging.file.name", logs.resolve("gestion.log").toString().replace('\\', '/'));
        }
    }

    /** Hors du dossier du programme : les données survivent à une mise à jour ou à une désinstallation. */
    private static String installedDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "GestionCommerce").toString();
        }
        return Path.of(System.getProperty("user.home"), ".gestion-commerce").toString();
    }

    private static boolean alreadyRunning(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url + "api/health").toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(2000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            try (InputStream body = connection.getInputStream()) {
                return new String(body.readAllBytes(), StandardCharsets.UTF_8).contains(APP_NAME);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void openInBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (IOException e) {
            System.err.println("Impossible d'ouvrir le navigateur. Ouvrez " + url + " à la main.");
        }
    }
}
