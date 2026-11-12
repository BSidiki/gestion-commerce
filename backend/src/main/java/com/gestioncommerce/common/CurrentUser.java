package com.gestioncommerce.common;

/**
 * La personne connectée pour la requête en cours. Renseignée par l'intercepteur
 * d'authentification, effacée à la fin de la requête.
 */
public final class CurrentUser {

    public static final String OWNER = "PROPRIETAIRE";
    public static final String SELLER = "VENDEUR";

    public record User(String id, String username, String displayName, String role) {

        public boolean isOwner() {
            return OWNER.equals(role);
        }
    }

    private static final ThreadLocal<User> CURRENT = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(User user) {
        CURRENT.set(user);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** La personne connectée, ou null (accès libre avant la création du premier compte). */
    public static User get() {
        return CURRENT.get();
    }

    public static boolean isOwner() {
        User user = CURRENT.get();
        return user != null && user.isOwner();
    }

    /** Nom d'utilisateur à inscrire sur une opération, ou null si personne n'est connecté. */
    public static String username() {
        User user = CURRENT.get();
        return user == null ? null : user.username();
    }
}
