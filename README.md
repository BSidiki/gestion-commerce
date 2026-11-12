# Gestion commerciale local-first

Logiciel de gestion interne pour commerçants : stock, ventes, crédits clients, dépenses.
Les données restent sur le PC du commerçant ; aucune fonction ne demande Internet.

## État du projet

Phases 1 et 2 du plan : catalogue, stock, ventes, crédits clients, dépenses et clôture de caisse, backend et interface.

| Fait | À venir |
| --- | --- |
| Socle Spring Boot + SQLite (WAL, `synchronous=FULL`) + migrations Flyway | Interface servie par le backend (un seul programme) |
| Catégories, produits, conditionnements, prix par niveau, codes-barres | Comptes utilisateurs et rôles |
| Réceptions, ajustements motivés, journal des mouvements | Impression sur imprimante thermique |
| Ventes atomiques, paiements, crédits clients, remboursements, annulation, rapport du jour | |
| Coût moyen pondéré, alertes de seuil | Import du catalogue depuis Excel |
| Interface Angular : liste des produits, création, fiche produit, réception, correction de stock | |
| Écran de caisse (recherche par nom ou code-barres, panier, encaissement, crédit, reçu) et ventes du jour | |
| Clients et remboursements, dépenses, ouverture et clôture de caisse avec écart | |
| Sauvegarde vérifiée (clé USB ou dossier), sauvegarde automatique à la clôture, restauration | |
| Sauvegardes chiffrées par mot de passe, avec clé de secours | |
| Installateur Windows : un seul programme avec son propre Java, lancé par un raccourci | |
| Licence hors ligne : essai de 15 jours, licence signée de 3 mois à partir de son enregistrement, lecture seule à l'échéance | |
| Comptes propriétaire, secrétaire et vendeur, droits par rôle, journal des opérations sensibles | |
| Modification d'un produit (nom, seuil, prix, codes-barres, nouveaux conditionnements) et désactivation | |

## Prérequis

- JDK 21
- Maven 3.9 ou plus récent
- Node.js 22.22.3 ou plus récent, ou 24.15 ou plus récent (interface, exigé par Angular 22)

## Lancer

```bash
cd backend
mvn test                 # tests d'intégration sur une base SQLite temporaire
mvn spring-boot:run      # démarre le service sur http://127.0.0.1:8080
```

La base est créée dans `backend/data/gestion.db`. Pour choisir un autre dossier :
`mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dapp.data-dir=C:/ProgramData/GestionCommerce"`
ou la variable d'environnement `GESTION_DATA_DIR`.

### Interface

Dans un second terminal, le backend étant démarré :

```bash
cd frontend
npm install
npm start                # http://localhost:4200, les appels /api sont relayés vers le backend
```

### Un seul programme

Le backend sert aussi l'interface : après `npm run build` dans `frontend`, un `mvn package` dans `backend`
embarque l'interface dans le fichier `.jar`, et tout est accessible sur le port du backend.

```bash
cd frontend && npm run build
cd ../backend && mvn package
java -jar target/gestion-commerce-backend-0.1.0-SNAPSHOT.jar     # http://127.0.0.1:8080
```

### Comptes et droits

Tant qu'aucun compte n'existe, l'API est en accès libre et l'interface fait créer le compte du propriétaire.
Ensuite, toute l'API exige une session (cookie `GC_SESSION`, HttpOnly, SameSite=Strict, 12 heures d'inactivité).

| Opération | Vendeur | Secrétaire | Propriétaire |
| --- | --- | --- | --- |
| Vendre, encaisser, vendre à crédit, enregistrer un remboursement | oui | oui | oui |
| Recevoir de la marchandise, noter une dépense, ouvrir et clôturer la caisse | oui | oui | oui |
| Consulter produits, clients, ventes du jour ; faire une sauvegarde | oui | oui | oui |
| Créer, modifier, désactiver un produit ; créer une catégorie | non | oui | oui |
| Voir les coûts, les marges et le résultat du jour | non | oui | oui |
| Vendre sous le prix plancher | non | non | oui, inscrit au journal |
| Annuler une vente, corriger le stock | non | non | oui, inscrit au journal |
| Voir le journal ; gérer les comptes et la licence ; restaurer ou protéger les sauvegardes | non | non | oui |

Les mots de passe ne sont pas enregistrés : seulement leur empreinte PBKDF2-HMAC-SHA256 (210 000 itérations) et un
sel par compte. Cinq essais ratés bloquent la connexion à ce compte pendant une minute.

Les coûts d'achat sont masqués au vendeur dans l'interface, mais l'API des produits les renvoie encore à toute
personne connectée : ce n'est pas une barrière de sécurité.

### Licences

Le logiciel fonctionne 15 jours en essai, puis demande une licence signée, valable 3 mois par défaut à partir
du jour où elle est enregistrée. À l'échéance, il passe en lecture seule : la consultation et la sauvegarde
restent possibles.
La création de la clé de signature et l'émission des licences sont décrites dans [tools/README.md](tools/README.md).
Sans clé publique embarquée (poste de développement), aucune licence n'est demandée.

### Installateur Windows

`installer\build.ps1` construit l'installateur à remettre au commerçant. Voir [installer/README.md](installer/README.md).

## Conventions de données

- Montants en francs CFA entiers.
- Coûts unitaires en millièmes de franc (`avgCostMilli`), pour rester exacts quand un carton est divisé en unités.
- Quantités de stock toujours en unité de base du produit ; chaque conditionnement porte un facteur de conversion.
- Aucun mouvement de stock n'est modifié ni supprimé : une correction est un nouveau mouvement.

## API

| Méthode | Chemin | Rôle |
| --- | --- | --- |
| POST | `/api/categories` | Créer une catégorie |
| GET | `/api/categories` | Lister les catégories |
| POST | `/api/products` | Créer un produit avec ses conditionnements et ses prix |
| GET | `/api/products` | Lister les produits (`?lowStock=true` : sous le seuil d'alerte ; `?includeInactive=true` : avec les désactivés) |
| PUT | `/api/products/{id}` | Modifier un produit, ses prix, ses codes-barres ; ajouter des conditionnements |
| POST | `/api/products/{id}/active` | Désactiver ou réactiver un produit |
| GET | `/api/products/{id}` | Détail d'un produit |
| POST | `/api/stock/receptions` | Recevoir de la marchandise |
| POST | `/api/stock/adjustments` | Corriger le stock avec un motif |
| GET | `/api/catalog/sellable` | Catalogue complet pour la caisse (conditionnements, prix, codes-barres) |
| GET | `/api/products/{id}/movements` | Historique des mouvements d'un produit |
| POST | `/api/customers` | Créer un client |
| GET | `/api/customers` | Lister les clients avec leur solde (`?withDebt=true` : ceux qui doivent) |
| GET | `/api/customers/{id}` | Fiche client et historique de son compte |
| POST | `/api/customers/{id}/repayments` | Enregistrer un remboursement de crédit |
| POST | `/api/sales` | Valider une vente (lignes, paiements, crédit éventuel) |
| GET | `/api/sales` | Ventes d'une journée (`?date=AAAA-MM-JJ`, aujourd'hui par défaut) |
| GET | `/api/sales/{id}` | Détail d'une vente |
| POST | `/api/sales/{id}/cancel` | Annuler une vente avec un motif |
| GET | `/api/reports/daily` | Résumé du jour : chiffre d'affaires, marge, encaissements, crédits, dépenses, résultat estimé |
| POST | `/api/expenses` | Enregistrer une dépense |
| GET | `/api/expenses` | Dépenses d'une journée (`?date=AAAA-MM-JJ`, aujourd'hui par défaut) |
| GET | `/api/cash/current` | Caisse ouverte, avec les espèces attendues |
| POST | `/api/cash/open` | Ouvrir la caisse avec un fond de caisse |
| POST | `/api/cash/close` | Clôturer la caisse avec le montant compté ; calcule l'écart |
| GET | `/api/cash/sessions` | Les 30 dernières sessions de caisse |
| GET | `/api/auth/me` | Qui est connecté ; indique si le compte du propriétaire reste à créer |
| POST | `/api/auth/setup` | Créer le compte du propriétaire (une seule fois) |
| POST | `/api/auth/login`, `/api/auth/logout` | Ouvrir, fermer la session |
| POST | `/api/auth/password` | Changer son propre mot de passe |
| GET, POST | `/api/users` | Lister, créer les comptes (propriétaire) |
| POST | `/api/users/{id}/password`, `/api/users/{id}/active` | Redonner un mot de passe, désactiver ou réactiver un compte (propriétaire) |
| GET | `/api/audit` | Journal des opérations sensibles (propriétaire) |
| GET | `/api/license` | État de la licence et code d'installation |
| POST | `/api/license` | Enregistrer une licence |
| GET | `/api/backups` | État des sauvegardes, emplacements proposés, historique |
| POST | `/api/backups` | Créer une sauvegarde dans un dossier (dossier local par défaut) |
| GET | `/api/backups/files` | Archives présentes dans un dossier (`?directory=`) |
| POST | `/api/backups/restore` | Restaurer une archive, après copie de l'état actuel (`secret` si elle est protégée par un autre mot de passe) |
| POST | `/api/backups/encryption` | Protéger les sauvegardes par mot de passe, ou changer le mot de passe ; renvoie la clé de secours |

### Exemple

```bash
# Un savon vendu à l'unité et au carton de 24
curl -X POST http://127.0.0.1:8080/api/products -H "Content-Type: application/json" -d '{
  "name": "Savon de Marseille",
  "baseUnit": "pièce",
  "alertThreshold": 50,
  "packagings": [
    {"name": "Unité", "factor": 1, "barcode": "6001234567890",
     "prices": [{"level": "DETAIL", "price": 300, "floorPrice": 250}]},
    {"name": "Carton", "factor": 24,
     "prices": [{"level": "GROS", "price": 6500, "floorPrice": 6000}]}
  ]
}'

# Réception de 2 cartons pour 10 000 F (remplacer les identifiants)
curl -X POST http://127.0.0.1:8080/api/stock/receptions -H "Content-Type: application/json" -d '{
  "productId": "<id du produit>", "packagingId": "<id du carton>", "quantity": 2, "totalCost": 10000
}'

# 5 pièces cassées
curl -X POST http://127.0.0.1:8080/api/stock/adjustments -H "Content-Type: application/json" -d '{
  "productId": "<id du produit>", "quantityDelta": -5, "reason": "CASSE"
}'
```

### Règles de modification d'un produit

- Le contenu d'un conditionnement existant (son facteur) ne change pas : le stock déjà compté deviendrait faux. On ajoute un nouveau conditionnement à la place.
- Aucun conditionnement n'est supprimé, et un produit ne se supprime pas : il se désactive. Il disparaît alors de la caisse et des listes, et son historique reste.
- Les ventes passées gardent le nom et le prix du moment de la vente.
- Chaque changement de prix est inscrit au journal, avec l'ancien et le nouveau prix.

### Règles d'une vente

- La vente, ses lignes, ses paiements, son crédit et ses sorties de stock sont écrits ensemble, ou pas du tout.
- `idempotencyKey` est un identifiant unique généré par l'interface : renvoyer la même requête renvoie la même vente.
- Le prix appliqué ne peut pas descendre sous le prix plancher du conditionnement.
- Ce qui n'est pas encaissé part en crédit, et exige un client.
- Une vente ne se supprime pas : l'annulation remet le stock et retire le crédit par des écritures inverses.

### Règles de la caisse

- Une seule caisse ouverte à la fois.
- Espèces attendues = fond de caisse + espèces encaissées (ventes non annulées et remboursements) - dépenses en espèces.
- Les ventes restent possibles caisse fermée, mais elles n'entrent dans aucune clôture.
- L'écart est le montant compté moins le montant attendu : négatif s'il manque de l'argent.

### Sauvegarde et restauration

- Une sauvegarde est un fichier `.zip` contenant la base et une fiche (date, niveau du schéma, empreinte SHA-256).
- La copie utilise le mécanisme de sauvegarde en ligne de SQLite : elle est cohérente même pendant l'utilisation, et sa cohérence est vérifiée avant l'archivage.
- Chaque clôture de caisse déclenche une sauvegarde automatique dans `<dossier de données>/sauvegardes` ; les 7 dernières sont gardées.
- Un rappel s'affiche si aucune sauvegarde hors du dossier de données n'a été faite depuis 3 jours.
- La restauration vérifie l'archive (empreinte, cohérence, version), garde une copie de l'état actuel, puis remplace les données sans redémarrer le logiciel.
- Une fois un mot de passe défini, les archives sont chiffrées (fichiers `.gcbak`, AES-256-GCM). Chaque archive a sa propre clé, ouverte soit par le mot de passe, soit par la clé de secours.
- Le logiciel ne garde ni le mot de passe ni la clé de secours, seulement des clés dérivées (PBKDF2, 600 000 itérations) qui lui permettent de chiffrer sans les redemander. La clé de secours n'est affichée qu'une fois.
- Sur le PC d'origine, la restauration ne demande rien. Sur un autre PC, ou après un changement de mot de passe, elle demande le mot de passe ou la clé de secours de l'archive.
- Le chiffrement protège une clé USB perdue ; il ne protège pas d'un accès au PC lui-même, où la base est en clair.

Motifs d'ajustement : `PERTE`, `CASSE`, `VOL`, `ERREUR_SAISIE`, `INVENTAIRE`.
Niveaux de prix : `DETAIL`, `DEMI_GROS`, `GROS`.

## Organisation du code

```
backend/src/main/java/com/gestioncommerce/
├── Application.java      démarrage, préparation du dossier de données
├── common/               erreurs et réponses d'erreur de l'API, personne connectée
├── auth/                 comptes, sessions, droits par rôle
├── audit/                journal des opérations sensibles
├── system/               état du logiciel, renvoi des pages vers l'interface
├── catalog/              catégories, produits, conditionnements, prix
├── inventory/            réceptions, ajustements, mouvements, coût moyen
├── customers/            clients, compte client, remboursements
├── sales/                ventes, paiements, annulation, rapport du jour
├── cash/                 dépenses, ouverture et clôture de caisse
├── backup/               sauvegarde, restauration, historique
└── license/              essai, vérification des licences, lecture seule
backend/src/main/resources/db/migration/   schéma SQL versionné (Flyway)

installer/                script de construction, script Inno Setup, notice
tools/                    outil de l'éditeur pour créer la clé et émettre les licences

frontend/src/app/
├── core/                 client de l'API, formats d'affichage (FCFA, quantités, dates)
├── features/pos/         écran de caisse et reçu
├── features/sales/       ventes du jour, résumé, annulation
├── features/customers/   clients, compte client, remboursements
├── features/cash/        dépenses, ouverture et clôture de caisse
├── features/backup/      sauvegarde et restauration
├── features/auth/        connexion, première installation
├── features/users/       comptes, mon compte, journal
└── features/products/    liste, création et fiche produit
```
