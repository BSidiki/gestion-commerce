# Gestion commerciale local-first

Logiciel de gestion interne pour commerçants : stock, ventes, crédits clients, dépenses.
Les données restent sur le PC du commerçant ; aucune fonction ne demande Internet.

## État du projet

Phases 1 et 2 du plan : catalogue, stock, ventes, crédits clients, dépenses et clôture de caisse, backend et interface.

| Fait | À venir |
| --- | --- |
| Socle Spring Boot + SQLite (WAL, `synchronous=FULL`) + migrations Flyway | Interface servie par le backend (un seul programme) |
| Catégories, produits, conditionnements, prix par niveau, codes-barres | Comptes utilisateurs et rôles |
| Réceptions, ajustements motivés, journal des mouvements | Modification d'un produit |
| Ventes atomiques, paiements, crédits clients, remboursements, annulation, rapport du jour | |
| Coût moyen pondéré, alertes de seuil | Licence hors ligne |
| Interface Angular : liste des produits, création, fiche produit, réception, correction de stock | |
| Écran de caisse (recherche par nom ou code-barres, panier, encaissement, crédit, reçu) et ventes du jour | |
| Clients et remboursements, dépenses, ouverture et clôture de caisse avec écart | |
| Sauvegarde vérifiée (clé USB ou dossier), sauvegarde automatique à la clôture, restauration | |
| Sauvegardes chiffrées par mot de passe, avec clé de secours | |
| Installateur Windows : un seul programme avec son propre Java, lancé par un raccourci | |

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
| GET | `/api/products` | Lister les produits (`?lowStock=true` : sous le seuil d'alerte) |
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
├── common/               erreurs et réponses d'erreur de l'API
├── system/               état du logiciel, renvoi des pages vers l'interface
├── catalog/              catégories, produits, conditionnements, prix
├── inventory/            réceptions, ajustements, mouvements, coût moyen
├── customers/            clients, compte client, remboursements
├── sales/                ventes, paiements, annulation, rapport du jour
├── cash/                 dépenses, ouverture et clôture de caisse
└── backup/               sauvegarde, restauration, historique
backend/src/main/resources/db/migration/   schéma SQL versionné (Flyway)

installer/                script de construction, script Inno Setup, notice

frontend/src/app/
├── core/                 client de l'API, formats d'affichage (FCFA, quantités, dates)
├── features/pos/         écran de caisse et reçu
├── features/sales/       ventes du jour, résumé, annulation
├── features/customers/   clients, compte client, remboursements
├── features/cash/        dépenses, ouverture et clôture de caisse
├── features/backup/      sauvegarde et restauration
└── features/products/    liste, création et fiche produit
```
