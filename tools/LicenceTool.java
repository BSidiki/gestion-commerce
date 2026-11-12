import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outil de l'éditeur : crée la clé de signature et émet les licences.
 * Se lance directement avec Java 21, sans compilation :
 *
 *   java tools/LicenceTool.java nouvelle-cle
 *   java tools/LicenceTool.java emettre --installation ABCD-1234-EF56 --boutique "Alimentation Wend-Kuni"
 *   java tools/LicenceTool.java emettre --installation ABCD-1234-EF56 --boutique "..." --mois 6
 *
 * LA CLÉ PRIVÉE NE DOIT JAMAIS QUITTER LE POSTE DE L'ÉDITEUR. Elle n'est rangée ni dans le
 * dépôt Git ni dans le logiciel : quiconque la possède peut fabriquer des licences valides.
 * Gardez-en une copie hors ligne. Si elle est perdue, il faut en créer une nouvelle, publier
 * une nouvelle version du logiciel et réémettre toutes les licences.
 *
 * Le format de la licence est aussi lu par LicenseCodec.java (backend) : toute évolution
 * doit être faite des deux côtés.
 */
public class LicenceTool {

    private static final Path DEFAULT_KEY_DIR = Path.of(System.getProperty("user.home"), ".gestion-commerce-editeur");
    private static final String PRIVATE_KEY_FILE = "cle-privee-licences.txt";
    private static final Path PUBLIC_KEY_RESOURCE = Path.of("backend", "src", "main", "resources", "licence",
            "cle-publique.txt");
    private static final int DEFAULT_MONTHS = 3;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Map<String, String> options = options(args);
        switch (args[0]) {
            case "nouvelle-cle" -> newKey(options);
            case "emettre" -> issue(options);
            default -> usage();
        }
    }

    private static void newKey(Map<String, String> options) throws Exception {
        Path dir = options.containsKey("dossier") ? Path.of(options.get("dossier")) : DEFAULT_KEY_DIR;
        Path privateFile = dir.resolve(PRIVATE_KEY_FILE);
        if (Files.exists(privateFile)) {
            fail("Une clé privée existe déjà : " + privateFile.toAbsolutePath()
                    + "\nLa remplacer rendrait invalides toutes les licences déjà émises."
                    + "\nSupprimez ce fichier à la main si c'est vraiment ce que vous voulez.");
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        Files.createDirectories(dir);
        Files.writeString(privateFile, privateKey + "\n");
        System.out.println("Clé privée écrite dans : " + privateFile.toAbsolutePath());
        System.out.println("  -> à garder secrète, avec une copie hors ligne (clé USB rangée à part).");

        if (Files.isDirectory(PUBLIC_KEY_RESOURCE.getParent())) {
            Files.writeString(PUBLIC_KEY_RESOURCE, publicKey + "\n");
            System.out.println("Clé publique écrite dans : " + PUBLIC_KEY_RESOURCE);
            System.out.println("  -> à valider dans Git, puis reconstruire l'installateur.");
        } else {
            System.out.println("Clé publique (à copier dans " + PUBLIC_KEY_RESOURCE + ") :");
            System.out.println(publicKey);
        }
    }

    private static void issue(Map<String, String> options) throws Exception {
        String installId = require(options, "installation").trim().toUpperCase();
        String shop = require(options, "boutique").replaceAll("[\\r\\n]+", " ").trim();
        int months = options.containsKey("mois") ? Integer.parseInt(options.get("mois")) : DEFAULT_MONTHS;
        if (months < 1 || months > 120) {
            fail("--mois doit être compris entre 1 et 120.");
        }
        if (!installId.matches("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}")) {
            fail("Code d'installation invalide : « " + installId + " ». Il a la forme ABCD-1234-EF56 "
                    + "et se lit dans le logiciel, page Licence.");
        }
        Path keyFile = options.containsKey("cle")
                ? Path.of(options.get("cle"))
                : DEFAULT_KEY_DIR.resolve(PRIVATE_KEY_FILE);
        if (!Files.exists(keyFile)) {
            fail("Clé privée introuvable : " + keyFile.toAbsolutePath()
                    + "\nCréez-la d'abord : java tools/LicenceTool.java nouvelle-cle");
        }
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).replaceAll("\\s", ""))));

        LocalDate issuedOn = LocalDate.now();
        LocalDate expiresOn = issuedOn.plusMonths(months);
        String id = UUID.randomUUID().toString();
        String content = "v=1\n"
                + "id=" + id + "\n"
                + "boutique=" + shop + "\n"
                + "installation=" + installId + "\n"
                + "emise=" + issuedOn + "\n"
                + "expire=" + expiresOn + "\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(bytes);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String token = encoder.encodeToString(bytes) + "." + encoder.encodeToString(signer.sign());

        Path file = Path.of("licence-" + installId + "-expire-" + expiresOn + ".txt");
        Files.writeString(file, token + "\n");

        System.out.println("Licence pour : " + shop);
        System.out.println("Installation : " + installId);
        System.out.println("Valable du " + issuedOn + " au " + expiresOn + " inclus (" + months + " mois)");
        System.out.println("Identifiant  : " + id);
        System.out.println("Fichier      : " + file.toAbsolutePath());
        System.out.println();
        System.out.println("Texte à envoyer au commerçant (WhatsApp, clé USB), à coller dans la page Licence :");
        System.out.println();
        System.out.println(token);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i].substring(2), args[++i]);
            } else {
                fail("Argument inattendu : " + args[i]);
            }
        }
        return options;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            fail("Option manquante : --" + name);
        }
        return value;
    }

    private static void usage() {
        System.out.println("""
                Outil de licences de Gestion Commerce

                  java tools/LicenceTool.java nouvelle-cle [--dossier DOSSIER]
                      Crée la clé de signature. À faire une seule fois.

                  java tools/LicenceTool.java emettre --installation CODE --boutique "NOM" [--mois 3] [--cle FICHIER]
                      Émet une licence pour une installation. CODE se lit dans le logiciel, page Licence.
                """);
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
