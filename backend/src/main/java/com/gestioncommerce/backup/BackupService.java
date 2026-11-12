package com.gestioncommerce.backup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gestioncommerce.backup.BackupDtos.BackupFile;
import com.gestioncommerce.backup.BackupDtos.BackupOverview;
import com.gestioncommerce.backup.BackupDtos.BackupStatus;
import com.gestioncommerce.backup.BackupDtos.BackupView;
import com.gestioncommerce.backup.BackupDtos.Destination;
import com.gestioncommerce.backup.BackupDtos.Manifest;
import com.gestioncommerce.backup.BackupDtos.RestoreResult;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.Timestamps;

/**
 * Sauvegarde et restauration de la base.
 *
 * La copie passe par le mécanisme de sauvegarde en ligne de SQLite : elle est cohérente
 * même si le logiciel est en cours d'utilisation, et le fichier de la base n'est jamais
 * copié ni remplacé directement. Aucune méthode n'est transactionnelle : ces commandes
 * SQLite doivent s'exécuter hors transaction.
 */
@Service
public class BackupService {

    public static final String MANUAL = "MANUELLE";
    public static final String AUTOMATIC = "AUTOMATIQUE";
    public static final String BEFORE_RESTORE = "AVANT_RESTAURATION";
    public static final String RESTORE = "RESTAURATION";

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private static final String APP_NAME = "gestion-commerce";
    private static final String DB_ENTRY = "gestion.db";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String AUTOMATIC_PREFIX = "sauvegarde-auto-";
    private static final int AUTOMATIC_KEPT = 7;
    private static final int REMINDER_DAYS = 3;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final Flyway flyway;
    private final ObjectMapper json;
    private final Path dataDir;
    private final Path localDir;

    public BackupService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, Flyway flyway, ObjectMapper json,
            @Value("${app.data-dir}") String dataDir) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.flyway = flyway;
        this.json = json;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.localDir = this.dataDir.resolve("sauvegardes");
    }

    // ---------- Sauvegarde ----------

    /** Sauvegarde demandée par le commerçant. Sans dossier : dans le dossier local du logiciel. */
    public BackupView createManual(String directory) {
        return create(directory, MANUAL);
    }

    /**
     * Sauvegarde locale silencieuse, à la clôture de caisse. Un échec est journalisé
     * mais ne bloque jamais la clôture.
     */
    public void createAutomatic() {
        try {
            create(null, AUTOMATIC);
            pruneAutomatic();
        } catch (RuntimeException e) {
            log.warn("Sauvegarde automatique impossible : {}", e.getMessage());
        }
    }

    private BackupView create(String directory, String kind) {
        Path target = localDir;
        try {
            if (directory != null && !directory.isBlank()) {
                target = Path.of(directory.trim()).toAbsolutePath().normalize();
            }
            Files.createDirectories(target);
            Path archive = target.resolve(prefix(kind) + FILE_STAMP.format(LocalDateTime.now()) + ".zip");

            Path work = Files.createTempDirectory("gestion-sauvegarde");
            try {
                Path copy = work.resolve(DB_ENTRY);
                runSqliteCommand("backup to " + quote(copy));
                checkIntegrity(copy);
                Manifest manifest = new Manifest(APP_NAME, Timestamps.now(), schemaRank(copy), sha256(copy),
                        Files.size(copy));
                writeArchive(archive, copy, manifest);
            } finally {
                deleteQuietly(work);
            }
            return record(kind, archive, "REUSSIE", null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Sauvegarde vers {} impossible", target, e);
            recordQuietly(kind, target, e.getMessage());
            throw new BusinessException("La sauvegarde a échoué. Vérifiez que la clé USB ou le dossier « "
                    + target + " » est accessible et qu'il reste de la place.");
        }
    }

    /** Ne garde que les dernières sauvegardes automatiques du dossier local. */
    private void pruneAutomatic() {
        try (Stream<Path> files = Files.list(localDir)) {
            List<Path> automatic = files
                    .filter(p -> p.getFileName().toString().startsWith(AUTOMATIC_PREFIX))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (Path old : automatic.subList(Math.min(AUTOMATIC_KEPT, automatic.size()), automatic.size())) {
                Files.deleteIfExists(old);
            }
        } catch (IOException e) {
            log.warn("Nettoyage des sauvegardes automatiques impossible : {}", e.getMessage());
        }
    }

    // ---------- Restauration ----------

    /**
     * Remplace les données par celles d'une archive, après vérification. L'état actuel
     * est d'abord sauvegardé dans le dossier local, pour pouvoir revenir en arrière.
     */
    public RestoreResult restore(String archivePath) {
        Path archive;
        try {
            archive = Path.of(archivePath.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new BusinessException("Ce chemin de fichier n'est pas valide.");
        }
        if (!Files.isRegularFile(archive)) {
            throw new BusinessException("Fichier de sauvegarde introuvable : " + archive);
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("gestion-restauration");
            Path restored = work.resolve(DB_ENTRY);
            Manifest manifest = extract(archive, work);

            if (!APP_NAME.equals(manifest.app())) {
                throw new BusinessException("Ce fichier n'est pas une sauvegarde de ce logiciel.");
            }
            if (!sha256(restored).equalsIgnoreCase(manifest.sha256())) {
                throw new BusinessException("La sauvegarde est endommagée : son contenu ne correspond plus "
                        + "à son empreinte. Essayez une autre sauvegarde.");
            }
            checkIntegrity(restored);
            if (schemaRank(restored) > currentSchemaRank()) {
                throw new BusinessException("Cette sauvegarde vient d'une version plus récente du logiciel. "
                        + "Mettez le logiciel à jour avant de la restaurer.");
            }

            BackupView safety = create(null, BEFORE_RESTORE);

            runSqliteCommand("restore from " + quote(restored));
            // Une sauvegarde plus ancienne peut avoir un schéma en retard : on le remet à niveau.
            flyway.migrate();

            // L'historique vient d'être remplacé par celui de l'archive : on y remet ces deux lignes.
            record(BEFORE_RESTORE, Path.of(safety.path()), "REUSSIE", null);
            record(RESTORE, archive, "REUSSIE", "Sauvegarde du " + manifest.createdAt());
            return new RestoreResult(archive.toString(), manifest.createdAt(), safety.path());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Restauration depuis {} impossible", archive, e);
            throw new BusinessException("Ce fichier ne peut pas être restauré : ce n'est pas une sauvegarde "
                    + "valide du logiciel, ou elle est endommagée.");
        } finally {
            if (work != null) {
                deleteQuietly(work);
            }
        }
    }

    // ---------- Consultation ----------

    public BackupOverview overview() {
        return new BackupOverview(status(), destinations(), history());
    }

    public List<BackupView> history() {
        return jdbc.sql("""
                SELECT id, kind, path, size_bytes, external, status, message, created_at
                FROM backup_history
                ORDER BY created_at DESC, rowid DESC
                LIMIT 50
                """)
                .query((rs, i) -> {
                    long size = rs.getLong("size_bytes");
                    Long sizeOrNull = rs.wasNull() ? null : size;
                    return new BackupView(rs.getString("id"), rs.getString("kind"), rs.getString("path"),
                            sizeOrNull, rs.getInt("external") == 1, rs.getString("status"),
                            rs.getString("message"), rs.getString("created_at"));
                })
                .list();
    }

    private BackupStatus status() {
        String filter = " FROM backup_history WHERE status = 'REUSSIE' AND kind IN ('MANUELLE', 'AUTOMATIQUE')";
        String last = jdbc.sql("SELECT MAX(created_at)" + filter).query(String.class).optional().orElse(null);
        String lastExternal = jdbc.sql("SELECT MAX(created_at)" + filter + " AND external = 1")
                .query(String.class).optional().orElse(null);
        boolean reminder = lastExternal == null
                || Duration.between(Instant.parse(lastExternal), Instant.now()).toDays() >= REMINDER_DAYS;
        return new BackupStatus(last, lastExternal, reminder);
    }

    /** Le dossier local, puis un dossier dédié sur chaque lecteur présent (clé USB, disque externe). */
    private List<Destination> destinations() {
        List<Destination> destinations = new ArrayList<>();
        destinations.add(new Destination(localDir.toString(), "Ce PC (dossier du logiciel)",
                dataDir.toFile().getUsableSpace(), true, true));
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                // Un lecteur sans support (lecteur de cartes vide) annonce une taille nulle.
                if (root.getTotalSpace() > 0) {
                    destinations.add(new Destination(
                            root.toPath().resolve("Sauvegardes-Gestion").toString(),
                            "Lecteur " + root.getPath(), root.getUsableSpace(), false,
                            root.toPath().equals(dataDir.getRoot())));
                }
            }
        }
        return destinations;
    }

    /** Archives présentes dans un dossier, la plus récente en premier. */
    public List<BackupFile> filesIn(String directory) {
        Path dir;
        try {
            dir = directory == null || directory.isBlank()
                    ? localDir
                    : Path.of(directory.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new BusinessException("Ce chemin de dossier n'est pas valide.");
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<BackupFile> found = new ArrayList<>();
            for (Path file : files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip")).toList()) {
                found.add(new BackupFile(file.toString(), file.getFileName().toString(), Files.size(file),
                        Files.getLastModifiedTime(file).toInstant().toString()));
            }
            found.sort(Comparator.comparing(BackupFile::modifiedAt).reversed());
            return found;
        } catch (IOException e) {
            throw new BusinessException("Impossible de lire le dossier « " + dir + " ».");
        }
    }

    // ---------- Outils ----------

    private static String prefix(String kind) {
        return switch (kind) {
            case AUTOMATIC -> AUTOMATIC_PREFIX;
            case BEFORE_RESTORE -> "avant-restauration-";
            default -> "sauvegarde-";
        };
    }

    /** Exécute une commande propre au pilote SQLite (« backup to », « restore from »). */
    private void runSqliteCommand(String command) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(command);
            }
            return null;
        });
    }

    private static String quote(Path path) {
        String text = path.toAbsolutePath().toString();
        if (!text.contains("'")) {
            return "'" + text + "'";
        }
        if (!text.contains("\"")) {
            return "\"" + text + "\"";
        }
        throw new BusinessException("Le chemin contient à la fois une apostrophe et un guillemet : "
                + "choisissez un autre dossier.");
    }

    private static void checkIntegrity(Path database) throws IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new IOException("La base de la sauvegarde est endommagée.");
            }
        } catch (SQLException e) {
            throw new IOException("La base de la sauvegarde est illisible.", e);
        }
    }

    /** Niveau du schéma d'une base : le nombre de migrations appliquées. */
    private static int schemaRank(Path database) throws IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COALESCE(MAX(installed_rank), 0) FROM flyway_schema_history WHERE success = 1")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IOException("Le schéma de la sauvegarde est illisible.", e);
        }
    }

    private int currentSchemaRank() {
        return jdbc.sql("SELECT COALESCE(MAX(installed_rank), 0) FROM flyway_schema_history WHERE success = 1")
                .query(Integer.class)
                .single();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 indisponible.", e);
        }
    }

    private void writeArchive(Path archive, Path database, Manifest manifest) throws IOException {
        // Écriture dans un fichier temporaire puis renommage : jamais d'archive à moitié écrite.
        Path partial = archive.resolveSibling(archive.getFileName() + ".partiel");
        try {
            try (OutputStream out = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                zip.write(json.writeValueAsBytes(manifest));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(DB_ENTRY));
                Files.copy(database, zip);
                zip.closeEntry();
            }
            Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** Extrait la base dans le dossier de travail et renvoie la fiche de l'archive. */
    private Manifest extract(Path archive, Path work) throws IOException {
        Manifest manifest = null;
        boolean databaseFound = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (DB_ENTRY.equals(entry.getName())) {
                    Files.copy(zip, work.resolve(DB_ENTRY), StandardCopyOption.REPLACE_EXISTING);
                    databaseFound = true;
                } else if (MANIFEST_ENTRY.equals(entry.getName())) {
                    manifest = json.readValue(zip.readAllBytes(), Manifest.class);
                }
            }
        }
        if (!databaseFound || manifest == null) {
            throw new IOException("Archive sans base ou sans fiche.");
        }
        return manifest;
    }

    private BackupView record(String kind, Path path, String status, String message) {
        String id = UUID.randomUUID().toString();
        String now = Timestamps.now();
        Long size = null;
        try {
            if (Files.isRegularFile(path)) {
                size = Files.size(path);
            }
        } catch (IOException e) {
            // La taille est une information de confort : son absence n'empêche rien.
        }
        boolean external = !path.toAbsolutePath().normalize().startsWith(dataDir);
        jdbc.sql("""
                INSERT INTO backup_history (id, kind, path, size_bytes, external, status, message, created_at)
                VALUES (:id, :kind, :path, :size, :external, :status, :message, :createdAt)
                """)
                .param("id", id)
                .param("kind", kind)
                .param("path", path.toString())
                .param("size", size)
                .param("external", external ? 1 : 0)
                .param("status", status)
                .param("message", message)
                .param("createdAt", now)
                .update();
        return new BackupView(id, kind, path.toString(), size, external, status, message, now);
    }

    private void recordQuietly(String kind, Path path, String message) {
        try {
            record(kind, path, "ECHOUEE", message);
        } catch (RuntimeException e) {
            log.warn("Échec de sauvegarde non inscrit à l'historique : {}", e.getMessage());
        }
    }

    private static void deleteQuietly(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.debug("Dossier temporaire non supprimé : {}", directory);
        }
    }
}
