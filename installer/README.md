# Installateur Windows

Ce dossier produit `GestionCommerce-Installation-<version>.exe`, le fichier à remettre au commerçant.
Il contient le programme, l'interface et son propre Java : rien d'autre à installer sur le PC de la boutique.

## Construire l'installateur

À faire sur un PC Windows de développement.

### Prérequis

| Outil | Rôle | Vérification |
| --- | --- | --- |
| JDK 21 | compile le programme et fournit `jpackage` | `jpackage --version` |
| Maven 3.9 ou plus récent | construit le backend | `mvn -version` |
| Node.js 22.22.3 ou plus récent | construit l'interface | `node -v` |
| Inno Setup 6 | fabrique l'installateur | https://jrsoftware.org/isinfo.php |

### Commande

Depuis la racine du dépôt :

```powershell
powershell -ExecutionPolicy Bypass -File installer\build.ps1
```

Options : `-Version 0.2.0` pour le numéro de version, `-Port 8787` pour le port local,
`-SkipTests` pour ne pas relancer les tests.

Le script enchaîne quatre étapes :

1. compilation de l'interface (`frontend`) ;
2. compilation et tests du backend, qui embarque l'interface ;
3. assemblage du dossier du programme avec `jpackage` : `installer\build\GestionCommerce\` ;
4. fabrication de l'installateur avec Inno Setup : `installer\build\GestionCommerce-Installation-<version>.exe`.

Sans Inno Setup, le script s'arrête après l'étape 3. Le dossier obtenu suffit pour un essai :
lancez `installer\build\GestionCommerce\GestionCommerce.exe`.

## Ce que fait l'installateur

- Il installe pour l'utilisateur Windows courant, sans droits d'administrateur.
- Programme : `%LOCALAPPDATA%\Programs\GestionCommerce`.
- Raccourcis : menu Démarrer, Bureau (au choix), démarrage avec Windows (au choix, recommandé).
- À la fin, il propose d'ouvrir le logiciel.

## Comment le logiciel se comporte une fois installé

- Un double-clic sur le raccourci démarre le logiciel en arrière-plan, puis ouvre la caisse dans le
  navigateur à l'adresse `http://127.0.0.1:8787/`.
- Si le logiciel tourne déjà, le raccourci ouvre simplement la caisse : il n'y a jamais deux instances.
- Au démarrage de Windows, il se lance sans ouvrir le navigateur.
- Il n'écoute que sur le PC lui-même : rien n'est accessible depuis le réseau.

## Où sont les données

Tout est dans `%LOCALAPPDATA%\GestionCommerce`, séparé du programme :

| Élément | Emplacement |
| --- | --- |
| Base de données | `gestion.db` |
| Sauvegardes automatiques | `sauvegardes\` |
| Journal technique | `journaux\gestion.log` |

Ni une mise à jour ni une désinstallation ne touchent à ce dossier.

## Mettre à jour un commerçant

Lancez le nouvel installateur par-dessus l'ancien : il arrête le logiciel, remplace le programme et garde les
données. Le schéma de la base est mis à niveau au premier démarrage.

Faites une sauvegarde sur clé USB avant chaque mise à jour.

## Limites actuelles

- Le fichier n'est pas signé : Windows SmartScreen affiche « Windows a protégé votre ordinateur » au premier
  lancement. Il faut cliquer sur « Informations complémentaires » puis « Exécuter quand même ». Un certificat de
  signature de code supprime cet avertissement.
- Le logiciel n'a pas d'icône dans la zone de notification. Pour l'arrêter : Gestionnaire des tâches,
  `GestionCommerce.exe`, « Fin de tâche ». L'arrêter ainsi ne fait perdre aucune donnée enregistrée.
- Il est installé pour un seul compte Windows. Un autre compte du même PC aurait ses propres données.
- Aucune sauvegarde automatique n'est faite avant la mise à niveau du schéma : d'où la sauvegarde manuelle
  demandée avant une mise à jour.
