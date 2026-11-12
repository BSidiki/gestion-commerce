package com.gestioncommerce.common;

/** Écriture refusée parce que l'essai est terminé ou la licence expirée. Renvoyée en 403. */
public class ReadOnlyException extends RuntimeException {

    public ReadOnlyException() {
        super("Le logiciel est en lecture seule : la période d'essai est terminée ou la licence a expiré. "
                + "Vos données restent consultables et sauvegardables. Enregistrez une licence dans la page Licence.");
    }
}
