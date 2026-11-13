# Gestion commerciale local-first

Logiciel de gestion interne pour commerçants : stock, ventes, crédits clients, dépenses.
Les données restent sur le PC du commerçant ; aucune fonction ne demande Internet.

## État du projet

Phases 1 et 2 du plan : catalogue, stock, ventes, crédits clients, dépenses et clôture de caisse, backend et interface.

| Fait | À venir |
| --- | --- |
| Socle Spring Boot + SQLite (WAL, `synchronous=FULL`) + migrations Flyway | |
| Catégories, produits, conditionnements, prix par niveau, codes-barres | |
| Réceptions, ajustements motivés, journal des mouvements | |
| Ventes atomiques, paiements, crédits clients, remboursements, annulation, rapport du jour | |
| Coût moyen pondéré, alertes de seuil | |
| Interface Angular : liste des produits, création, fiche produit, réception, correction de stock | |
| Écran de caisse (recherche par nom ou code-barres, panier, encaissement, crédit, reçu) et ventes du jour | |
| Clients et remboursements, dépenses, ouverture et clôture de caisse avec écart | |
| Sauvegarde vérifiée (clé USB ou dossier), sauvegarde automatique à la clôture, restauration | |
| Sauvegardes chiffrées par mot de passe, avec clé de secours | |
| Installateur Windows : un seul programme avec son propre Java, lancé par un raccourci | |
| Licence hors ligne : essai de 15 jours, licence signée de 3 mois à partir de son enregistrement, lecture seule à l'échéance | |
| Comptes propriétaire, secrétaire et vendeur, droits par rôle, journal des opérations sensibles | |
| Modification d'un produit (nom, seuil, prix, codes-barres, nouveaux conditionnements) et désactivation | |
| Import du catalogue depuis Excel : copier-coller ou fichier CSV, vérification avant import | |
| Reçu imprimable sur imprimante thermique (58 ou 80 mm) ou A4, en-tête de la boutique, réimpression | |
| Apparence personnalisable : couleur, logo et nom de la boutique ; thème clair ou sombre et taille du texte par poste | |
| Tableau de bord : activité par période, produits les plus vendus, stock qui dort, crédits à relancer, export Excel | |
| Fournisseurs : achats qui entrent en stock, achats à crédit, dettes et paiements | |

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
| Voir le journal ; gérer les comptes, la licence et les coordonnées de la boutique ; restaurer ou protéger les sauvegardes | non | non | oui |

Les mots de passe ne sont pas enregistrés : seulement leur empreinte PBKDF2-HMAC-SHA256 (210 000 itérations) et un
sel par compte. Cinq essais ratés bloquent la connexion à ce compte pendant une minute.

Les coûts d'achat sont masqués au vendeur par le service lui-même : pour son compte, le coût moyen des produits
et le coût des mouvements de stock ne figurent pas dans les réponses. Il saisit toujours le coût d'une réception
qu'il enregistre.

Le propriétaire change le rôle d'un compte depuis la page Utilisateurs ; l'effet est immédiat, sans reconnexion.
Il doit toujours rester un propriétaire actif.

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
| POST | `/api/products/import` | Importer un tableau de produits (`dryRun: true` : vérifier sans rien enregistrer) |
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
| POST | `/api/users/{id}/role` | Changer le rôle d'un compte (propriétaire) |
| GET | `/api/audit` | Journal des opérations sensibles (propriétaire) |
| GET, POST | `/api/suppliers` | Lister les fournisseurs avec ce qui leur est dû, en créer un |
| GET, PUT | `/api/suppliers/{id}` | Fiche et historique d'un fournisseur, modifier ses coordonnées |
| POST | `/api/suppliers/{id}/payments` | Payer une dette envers le fournisseur |
| POST | `/api/purchases` | Enregistrer une livraison : entrée en stock, dette pour ce qui n'est pas payé |
| GET | `/api/purchases/{id}` | Détail d'un achat |
| GET | `/api/reports/period?from=&to=` | Activité entre deux dates : totaux, journées, meilleurs produits, dépenses par catégorie, ventes par personne |
| GET | `/api/reports/stock?dormantDays=30` | Valeur du stock, produits sous le seuil, produits en stock sans vente récente |
| GET | `/api/reports/credits` | Clients qui doivent de l'argent, et depuis quand ils n'ont rien payé |
| GET | `/api/branding` | Nom, couleur et logo de la boutique, lisibles sans session (écran de connexion) |
| GET | `/api/shop` | Coordonnées de la boutique, apparence et format du reçu |
| PUT | `/api/shop` | Les modifier (propriétaire) |
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

### Import du catalogue depuis Excel

Page **Produits → Importer depuis Excel**, ouverte au propriétaire et à la secrétaire. On copie les cellules
du tableur, titres compris, et on les colle ; un fichier CSV convient aussi. Un modèle se télécharge depuis la page.

- Colonnes reconnues, dans n'importe quel ordre : Nom (seule obligatoire), Catégorie, Unité, Prix détail,
  Prix plancher, Code-barres, Seuil d'alerte, Stock initial, Coût d'achat, Conditionnement, Contenu, Prix gros,
  Prix plancher gros. Les colonnes inconnues sont ignorées et signalées.
- « Vérifier le tableau » joue l'import sans rien enregistrer et donne le résultat ligne par ligne.
- Une ligne en erreur n'empêche pas les autres. Un produit dont le nom existe déjà est laissé tel quel :
  relancer le même import ne crée pas de doublon, et ne modifie aucun produit existant.
- Le stock initial entre comme une réception, valorisée au coût d'achat indiqué. Les catégories absentes sont créées.
- Au plus 2 000 lignes par import. Un classeur `.xlsx` ne se lit pas directement : copier-coller ou enregistrer en CSV.

### Fournisseurs et achats

Pages **Fournisseurs**, ouvertes au propriétaire et à la secrétaire (elles montrent les coûts d'achat).

- **Un achat** est une livraison d'un fournisseur, en une ou plusieurs lignes : article, conditionnement reçu,
  quantité, prix d'achat. À l'enregistrement, chaque ligne entre en stock et met à jour le coût moyen du produit.
  L'achat entier est enregistré, ou rien.
- **Ce qui n'est pas payé à la livraison devient une dette** envers le fournisseur. La fiche du fournisseur
  montre ce qui reste à payer, permet de payer, et garde l'historique des achats et des paiements.
- **Un paiement ne dépasse jamais la dette.** S'il est fait en espèces avec l'argent du tiroir, on le coche :
  il est alors retiré des espèces attendues à la clôture de la caisse. Sinon, la caisse n'est pas touchée.
- Un achat n'est pas une dépense : son coût entre dans le résultat au moment où la marchandise est vendue
  (coût des marchandises vendues), pas au moment où elle est payée.
- Le vendeur garde la réception simple, depuis la fiche d'un produit, sans fournisseur.
- Limite : un achat enregistré ne s'annule pas. Une erreur de quantité se corrige par un ajustement de stock.

### Tableau de bord

Page **Tableau de bord**, ouverte au propriétaire et à la secrétaire (tous les rapports montrent des marges).

- **Période** : aujourd'hui, 7 jours, 30 jours, ce mois, le mois dernier, ou deux dates au choix (un an au plus).
  Au-delà de deux mois, le graphique regroupe les journées par mois.
- **Activité** : chiffre d'affaires, marge brute, dépenses, résultat estimé, encaissements, ventes à crédit ;
  produits les plus vendus ; dépenses par catégorie ; ventes par personne. Les ventes annulées sont exclues.
- **Stock** : valeur au coût d'achat moyen, produits sous le seuil, et produits en stock qui ne se sont pas
  vendus depuis 30, 60, 90 ou 180 jours.
- **Crédits clients** : ce qui reste à encaisser, client par client, avec le nombre de jours écoulés depuis leur
  dernier paiement (ou depuis leur premier crédit s'ils n'ont jamais payé). Au-delà de 30 jours : « À relancer ».
- **Exporter pour Excel** : les journées de la période dans un fichier CSV.

### Apparence

L'application se met aux couleurs de la boutique. Deux niveaux de réglage :

- **Pour toute la boutique** (page Boutique, propriétaire) : le nom, le logo et la couleur, à choisir parmi neuf
  teintes. Ils s'appliquent à tous les postes, à l'écran de connexion et au reçu. La couleur s'essaie à l'écran
  avant d'être enregistrée. Le logo est réduit à 320 points de côté avant l'envoi (PNG, JPEG ou WebP).
- **Pour le poste** (page Mon compte, tout le monde) : thème clair, sombre ou « comme Windows », et taille du
  texte (normale, grande, très grande). Ces deux réglages restent dans le navigateur du poste.

Les teintes sont définies dans `frontend/src/styles.css` (`data-accent`) et nommées dans
`frontend/src/app/core/appearance.ts` et `ShopService.ACCENTS` : pour en ajouter une, compléter les trois.
Les polices (Figtree, Bricolage Grotesque) sont embarquées : rien n'est téléchargé à l'exécution.

### Impression du reçu

Le reçu s'imprime par la fenêtre d'impression du navigateur, sur l'imprimante installée dans Windows :
aucun pilote particulier n'est demandé au logiciel.

- Page **Boutique** (propriétaire) : nom, adresse, téléphone, phrase de fin de reçu, et format du papier —
  rouleau de 80 mm, rouleau de 58 mm, ou feuille A4. Un aperçu se met à jour pendant la saisie, et
  « Imprimer un essai » sort un reçu d'exemple.
- En 80 mm le reçu occupe 72 mm de large, en 58 mm il en occupe 48 : la largeur imprimable habituelle de ces rouleaux.
- À la première impression, dans la fenêtre d'impression : choisir l'imprimante, mettre les marges sur
  « Aucune » et décocher « En-têtes et pieds de page ». Le navigateur retient ces choix.
- Un reçu se réimprime depuis **Ventes du jour**, bouton « Reçu ». Celui d'une vente annulée porte la mention « VENTE ANNULÉE ».

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
├── suppliers/            fournisseurs, achats, paiements
├── reports/              rapports par période, stock, crédits
├── license/              essai, vérification des licences, lecture seule
└── shop/                 coordonnées de la boutique, format du reçu
backend/src/main/resources/db/migration/   schéma SQL versionné (Flyway)

installer/                script de construction, script Inno Setup, notice
tools/                    outil de l'éditeur pour créer la clé et émettre les licences

frontend/src/app/
├── core/                 client de l'API, formats d'affichage (FCFA, quantités, dates)
├── features/pos/         écran de caisse et reçu
├── features/sales/       ventes du jour, résumé, annulation, reçu
├── features/suppliers/   fournisseurs, fiche, saisie d'un achat
├── features/reports/     tableau de bord
├── features/shop/        coordonnées de la boutique, format du reçu
├── features/customers/   clients, compte client, remboursements
├── features/cash/        dépenses, ouverture et clôture de caisse
├── features/backup/      sauvegarde et restauration
├── features/auth/        connexion, première installation
├── features/users/       comptes, mon compte, journal
└── features/products/    liste, création, fiche produit, import
```
