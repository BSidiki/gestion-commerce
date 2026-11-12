# Licences : mode d'emploi de l'éditeur

Le logiciel vérifie ses licences sans Internet. Il fonctionne 15 jours en essai, puis demande une licence.
Une licence est valable 3 mois par défaut. À l'expiration, le logiciel passe en lecture seule : tout reste
consultable et sauvegardable, mais plus rien ne s'enregistre.

Deux clés sont en jeu :

| Clé | Où elle se trouve | Rôle |
| --- | --- | --- |
| Clé privée | uniquement sur le poste de l'éditeur, dans `%USERPROFILE%\.gestion-commerce-editeur\` | signe les licences |
| Clé publique | dans le logiciel (`backend/src/main/resources/licence/cle-publique.txt`) | vérifie les licences |

**La clé privée ne doit jamais être envoyée, partagée ni validée dans Git.** Quiconque la possède peut fabriquer
des licences valides. Gardez-en une copie hors ligne : si elle est perdue, il faut en créer une nouvelle, publier
une nouvelle version du logiciel et réémettre toutes les licences.

## 1. Créer la clé de signature (une seule fois)

Depuis la racine du dépôt :

```powershell
java tools/LicenceTool.java nouvelle-cle
```

L'outil écrit la clé privée dans votre dossier personnel et la clé publique dans le backend.
Validez ensuite la clé publique dans Git et reconstruisez l'installateur :

```powershell
git add backend/src/main/resources/licence/cle-publique.txt
git commit -m "Clé publique des licences"
powershell -ExecutionPolicy Bypass -File installer\build.ps1
```

Tant que le fichier de clé publique n'existe pas, le logiciel ne demande aucune licence : c'est le
comportement voulu sur un poste de développement, pas pour une version remise à un commerçant.

## 2. Émettre une licence pour un commerçant

1. Le commerçant ouvre la page **Licence** du logiciel et vous communique son **code d'installation**
   (par exemple `3F9A-C21E-7B40`).
2. Vous émettez la licence :

   ```powershell
   java tools/LicenceTool.java emettre --installation 3F9A-C21E-7B40 --boutique "Alimentation Wend-Kuni"
   ```

   Ajoutez `--mois 6` pour une autre durée que 3 mois.
3. L'outil affiche le texte de la licence et l'enregistre dans un fichier `licence-<code>-expire-<date>.txt`.
4. Envoyez ce texte au commerçant (WhatsApp, clé USB, papier). Il le colle dans la page Licence.

Le renouvellement se fait de la même façon, avec le même code d'installation.

## Ce qu'il faut savoir

- Une licence ne fonctionne que pour le code d'installation indiqué.
- Le code d'installation est rangé avec les données : après une restauration sur un PC neuf, la licence en cours
  reste valable, sans nouvelle émission.
- Reculer l'horloge du PC ne prolonge ni l'essai ni la licence : le logiciel retient la date la plus tardive
  qu'il a vue.
- La durée court à partir du jour d'émission, pas du jour où le commerçant colle la licence.
- Tenez un registre des licences émises (boutique, code, date d'expiration) : l'outil n'en garde pas.
