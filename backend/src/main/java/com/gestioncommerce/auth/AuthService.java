package com.gestioncommerce.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.auth.PasswordHasher.Hashed;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.CurrentUser;
import com.gestioncommerce.common.CurrentUser.User;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.common.Timestamps;
import com.gestioncommerce.common.TooManyAttemptsException;
import com.gestioncommerce.common.UnauthorizedException;

/**
 * Comptes et sessions.
 *
 * Trois rôles : le vendeur (vendre, encaisser, recevoir de la marchandise, consulter),
 * la secrétaire (en plus : gérer le catalogue et voir les marges) et le propriétaire
 * (tous les droits). Tant qu'aucun compte n'existe, le logiciel est en accès libre ;
 * le premier compte créé est celui du propriétaire.
 */
@Service
public class AuthService {

    public static final Duration SESSION_DURATION = Duration.ofHours(12);
    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(1);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String USER_COLUMNS = "id, username, display_name, role, active";

    private final JdbcClient jdbc;
    private final AuditService audit;

    /** Essais ratés par nom d'utilisateur. En mémoire : remis à zéro au redémarrage. */
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private record Failures(int count, Instant lockedUntil) {
    }

    public AuthService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** @param token jeton de session, à remettre au navigateur dans un cookie */
    public record Login(String token, User user) {
    }

    public record UserView(String id, String username, String displayName, String role, boolean active) {
    }

    // ---------- État ----------

    public boolean hasUsers() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single() > 0;
    }

    // ---------- Connexion ----------

    /** Crée le compte du propriétaire au premier lancement, et le connecte. */
    @Transactional
    public Login setup(String username, String displayName, String password) {
        if (hasUsers()) {
            throw new BusinessException("Le compte du propriétaire existe déjà. Connectez-vous.");
        }
        String id = insertUser(username, displayName, CurrentUser.OWNER, password);
        User owner = new User(id, username.trim(), displayName.trim(), CurrentUser.OWNER);
        audit.log(owner.username(), "COMPTE_CREE", "Compte du propriétaire créé");
        return new Login(openSession(id), owner);
    }

    public Login login(String username, String password) {
        String key = username == null ? "" : username.trim().toLowerCase();
        Failures previous = failures.get(key);
        if (previous != null && previous.lockedUntil() != null && Instant.now().isBefore(previous.lockedUntil())) {
            throw new TooManyAttemptsException("Trop d'essais ratés. Réessayez dans une minute.");
        }

        Optional<StoredUser> stored = jdbc.sql("""
                SELECT id, username, display_name, role, active, password_hash, password_salt, iterations
                FROM app_user
                WHERE username = ? AND active = 1
                """)
                .param(key)
                .query((rs, i) -> new StoredUser(mapUser(rs),
                        new Hashed(rs.getString("password_hash"), rs.getString("password_salt"),
                                rs.getInt("iterations"))))
                .optional();

        if (stored.isEmpty() || !PasswordHasher.matches(password, stored.get().password())) {
            int count = (previous == null ? 0 : previous.count()) + 1;
            boolean lock = count >= MAX_FAILURES;
            failures.put(key, new Failures(lock ? 0 : count, lock ? Instant.now().plus(LOCK_DURATION) : null));
            throw new UnauthorizedException("Nom d'utilisateur ou mot de passe incorrect.");
        }
        failures.remove(key);
        User user = stored.get().user();
        return new Login(openSession(user.id()), user);
    }

    /** La personne à qui appartient ce jeton, si la session est encore valable. */
    public Optional<User> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hashToken(token);
        Optional<SessionRow> row = jdbc.sql("""
                SELECT u.id, u.username, u.display_name, u.role, u.active, s.expires_at
                FROM user_session s
                JOIN app_user u ON u.id = s.user_id
                WHERE s.token_hash = ? AND u.active = 1
                """)
                .param(tokenHash)
                .query((rs, i) -> new SessionRow(mapUser(rs), rs.getString("expires_at")))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Instant expiresAt = Instant.parse(row.get().expiresAt());
        if (!now.isBefore(expiresAt)) {
            jdbc.sql("DELETE FROM user_session WHERE token_hash = ?").param(tokenHash).update();
            return Optional.empty();
        }
        // La session se prolonge tant qu'on s'en sert ; on n'écrit que lorsqu'elle est à moitié consommée.
        if (Duration.between(now, expiresAt).compareTo(SESSION_DURATION.dividedBy(2)) < 0) {
            jdbc.sql("UPDATE user_session SET expires_at = ? WHERE token_hash = ?")
                    .params(Timestamps.of(now.plus(SESSION_DURATION)), tokenHash)
                    .update();
        }
        return Optional.of(row.get().user());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            jdbc.sql("DELETE FROM user_session WHERE token_hash = ?").param(hashToken(token)).update();
        }
    }

    // ---------- Gestion des comptes ----------

    public List<UserView> listUsers() {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user ORDER BY username")
                .query((rs, i) -> new UserView(rs.getString("id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("role"), rs.getInt("active") == 1))
                .list();
    }

    @Transactional
    public UserView createUser(String username, String displayName, String role, String password) {
        if (!CurrentUser.isKnownRole(role)) {
            throw new BusinessException("Rôle inconnu.");
        }
        String id = insertUser(username, displayName, role, password);
        audit.log("COMPTE_CREE", "Compte « " + username.trim() + " » créé (" + role + ")");
        return new UserView(id, username.trim(), displayName.trim(), role, true);
    }

    /** Changement de son propre mot de passe : l'ancien est exigé. */
    @Transactional
    public void changeOwnPassword(User user, String currentPassword, String newPassword) {
        Hashed stored = jdbc.sql("SELECT password_hash, password_salt, iterations FROM app_user WHERE id = ?")
                .param(user.id())
                .query((rs, i) -> new Hashed(rs.getString("password_hash"), rs.getString("password_salt"),
                        rs.getInt("iterations")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Compte introuvable."));
        if (!PasswordHasher.matches(currentPassword, stored)) {
            throw new BusinessException("Le mot de passe actuel est incorrect.");
        }
        updatePassword(user.id(), newPassword);
        audit.log("MOT_DE_PASSE_CHANGE", "Mot de passe de « " + user.username() + " » changé par lui-même");
    }

    /** Le propriétaire redonne un mot de passe à quelqu'un qui a oublié le sien. */
    @Transactional
    public void resetPassword(String userId, String newPassword) {
        String username = usernameOf(userId);
        updatePassword(userId, newPassword);
        jdbc.sql("DELETE FROM user_session WHERE user_id = ?").param(userId).update();
        audit.log("MOT_DE_PASSE_CHANGE", "Mot de passe de « " + username + " » réinitialisé");
    }

    /** Dépannage au démarrage (voir PasswordResetRunner) : le compte est aussi réactivé. */
    @Transactional
    public void resetPasswordByUsername(String username, String newPassword) {
        String userId = jdbc.sql("SELECT id FROM app_user WHERE username = ?")
                .param(username.trim())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Compte introuvable."));
        updatePassword(userId, newPassword);
        jdbc.sql("UPDATE app_user SET active = 1 WHERE id = ?").param(userId).update();
        jdbc.sql("DELETE FROM user_session WHERE user_id = ?").param(userId).update();
        audit.log(null, "MOT_DE_PASSE_CHANGE", "Mot de passe de « " + username.trim()
                + " » réinitialisé au démarrage du logiciel");
    }

    @Transactional
    /** Change le rôle d'un compte. Il doit toujours rester un propriétaire actif. */
    @Transactional
    public UserView changeRole(String userId, String role) {
        if (!CurrentUser.isKnownRole(role)) {
            throw new BusinessException("Rôle inconnu.");
        }
        String username = usernameOf(userId);
        String previous = roleOf(userId);
        if (!previous.equals(role)) {
            boolean lastOwner = CurrentUser.OWNER.equals(previous) && jdbc.sql("""
                    SELECT COUNT(*) FROM app_user
                    WHERE role = 'PROPRIETAIRE' AND active = 1 AND id <> ?
                    """)
                    .param(userId).query(Long.class).single() == 0;
            if (lastOwner) {
                throw new BusinessException("Il doit rester un propriétaire : nommez-en un autre d'abord.");
            }
            jdbc.sql("UPDATE app_user SET role = ? WHERE id = ?").params(role, userId).update();
            audit.log("ROLE_CHANGE", "Compte « " + username + " » : " + previous + " → " + role);
        }
        return listUsers().stream().filter(u -> u.id().equals(userId)).findFirst().orElseThrow();
    }

    public void setActive(String userId, boolean active) {
        String username = usernameOf(userId);
        if (!active) {
            boolean lastOwner = jdbc.sql("""
                    SELECT COUNT(*) FROM app_user
                    WHERE role = 'PROPRIETAIRE' AND active = 1 AND id <> ?
                    """)
                    .param(userId).query(Long.class).single() == 0
                    && CurrentUser.OWNER.equals(roleOf(userId));
            if (lastOwner) {
                throw new BusinessException("Impossible de désactiver le dernier compte propriétaire.");
            }
            jdbc.sql("DELETE FROM user_session WHERE user_id = ?").param(userId).update();
        }
        jdbc.sql("UPDATE app_user SET active = ? WHERE id = ?").params(active ? 1 : 0, userId).update();
        audit.log(active ? "COMPTE_REACTIVE" : "COMPTE_DESACTIVE", "Compte « " + username + " »");
    }

    // ---------- Interne ----------

    private record StoredUser(User user, Hashed password) {
    }

    private record SessionRow(User user, String expiresAt) {
    }

    private String insertUser(String username, String displayName, String role, String password) {
        String name = username == null ? "" : username.trim();
        if (!name.matches("[A-Za-z0-9._-]{3,30}")) {
            throw new BusinessException("Le nom d'utilisateur doit faire 3 à 30 caractères : lettres sans accent, "
                    + "chiffres, point, tiret.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException("Indiquez le nom de la personne.");
        }
        requireStrongEnough(password);
        boolean taken = jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username = ?")
                .param(name).query(Long.class).single() > 0;
        if (taken) {
            throw new BusinessException("Le nom d'utilisateur « " + name + " » est déjà pris.");
        }
        Hashed hashed = PasswordHasher.hash(password);
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO app_user (id, username, display_name, role, password_hash, password_salt,
                                      iterations, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(id, name, displayName.trim(), role, hashed.hash(), hashed.salt(), hashed.iterations(),
                        Timestamps.now())
                .update();
        return id;
    }

    private void updatePassword(String userId, String newPassword) {
        requireStrongEnough(newPassword);
        Hashed hashed = PasswordHasher.hash(newPassword);
        jdbc.sql("UPDATE app_user SET password_hash = ?, password_salt = ?, iterations = ? WHERE id = ?")
                .params(hashed.hash(), hashed.salt(), hashed.iterations(), userId)
                .update();
    }

    private static void requireStrongEnough(String password) {
        if (password == null || password.length() < PasswordHasher.MIN_LENGTH) {
            throw new BusinessException("Le mot de passe doit contenir au moins " + PasswordHasher.MIN_LENGTH
                    + " caractères.");
        }
    }

    private String usernameOf(String userId) {
        return jdbc.sql("SELECT username FROM app_user WHERE id = ?")
                .param(userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Compte introuvable."));
    }

    private String roleOf(String userId) {
        return jdbc.sql("SELECT role FROM app_user WHERE id = ?").param(userId).query(String.class).single();
    }

    private String openSession(String userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        jdbc.sql("INSERT INTO user_session (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
                .params(hashToken(token), userId, Timestamps.of(now), Timestamps.of(now.plus(SESSION_DURATION)))
                .update();
        return token;
    }

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible.", e);
        }
    }

    private static User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"));
    }
}
