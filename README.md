# Gestion commerciale local-first

Logiciel de gestion interne pour commerçants : stock, ventes, crédits clients, dépenses.
Les données restent sur le PC du commerçant ; aucune fonction ne demande Internet.

## État du projet

Phase 1 (catalogue et stock) : backend et interface. Phase 2 (ventes) : backend.

| Fait | À venir |
| --- | --- |
| Socle Spring Boot + SQLite (WAL, `synchronous=FULL`) + migrations Flyway | Interface servie par le backend (un seul programme) |
| Catégories, produits, conditionnements, prix par niveau, codes-barres | Comptes utilisateurs et rôles |
| Réceptions, ajustements motivés, journal des mouvements | Interface de caisse, dépenses, clôture de caisse |
| Ventes atomiques, paiements, crédits clients, remboursements, annulation, rapport du jour | |
| Coût moyen pondéré, alertes de seuil | Sauvegarde, licence, installateur (phase 3) |
| Interface Angular : liste des produits, création, fiche produit, réception, correction de stock | |

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
| GET | `/api/products/{id}/movements` | Historique des mouvements d'un produit |
| POST | `/api/customers` | Créer un client |
| GET | `/api/customers` | Lister les clients avec leur solde (`?withDebt=true` : ceux qui doivent) |
| GET | `/api/customers/{id}` | Fiche client et historique de son compte |
| POST | `/api/customers/{id}/repayments` | Enregistrer un remboursement de crédit |
| POST | `/api/sales` | Valider une vente (lignes, paiements, crédit éventuel) |
| GET | `/api/sales` | Ventes d'une journée (`?date=AAAA-MM-JJ`, aujourd'hui par défaut) |
| GET | `/api/sales/{id}` | Détail d'une vente |
| POST | `/api/sales/{id}/cancel` | Annuler une vente avec un motif |
| GET | `/api/reports/daily` | Résumé du jour : chiffre d'affaires, marge, encaissements, crédits |

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

Motifs d'ajustement : `PERTE`, `CASSE`, `VOL`, `ERREUR_SAISIE`, `INVENTAIRE`.
Niveaux de prix : `DETAIL`, `DEMI_GROS`, `GROS`.

## Organisation du code

```
backend/src/main/java/com/gestioncommerce/
├── Application.java      démarrage, préparation du dossier de données
├── common/               erreurs et réponses d'erreur de l'API
├── catalog/              catégories, produits, conditionnements, prix
├── inventory/            réceptions, ajustements, mouvements, coût moyen
├── customers/            clients, compte client, remboursements
└── sales/                ventes, paiements, annulation, rapport du jour
backend/src/main/resources/db/migration/   schéma SQL versionné (Flyway)

frontend/src/app/
├── core/                 client de l'API, formats d'affichage (FCFA, quantités, dates)
└── features/products/    liste, création et fiche produit
```
