package com.gestioncommerce.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.catalog.CatalogDtos.PackagingRequest;
import com.gestioncommerce.catalog.CatalogDtos.PackagingView;
import com.gestioncommerce.catalog.CatalogDtos.PriceRequest;
import com.gestioncommerce.catalog.CatalogDtos.ProductDetail;
import com.gestioncommerce.catalog.CatalogDtos.ProductRequest;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.Timestamps;
import com.gestioncommerce.inventory.StockDtos.ReceptionRequest;
import com.gestioncommerce.inventory.StockService;

/**
 * Import du catalogue : une ligne par produit, telle que collée depuis un tableur.
 *
 * Chaque ligne est traitée dans sa propre transaction : une ligne en erreur n'empêche pas
 * les autres. En mode vérification, chaque ligne est jouée puis annulée : on obtient le
 * résultat exact sans rien enregistrer.
 */
@Service
public class CatalogImportService {

    public static final int MAX_ROWS = 2000;

    public static final String CREATED = "CREE";
    public static final String SKIPPED = "EXISTE";
    public static final String ERROR = "ERREUR";

    private final JdbcClient jdbc;
    private final CatalogService catalog;
    private final StockService stock;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public CatalogImportService(JdbcClient jdbc, CatalogService catalog, StockService stock, AuditService audit,
            TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.stock = stock;
        this.audit = audit;
        this.transaction = transaction;
    }

    /**
     * Une ligne du tableau. Seul le nom est obligatoire. Les montants sont en francs CFA entiers.
     *
     * @param baseUnit        la plus petite quantité vendue ; « pièce » si absent
     * @param retailPrice     prix de vente au détail de l'unité de base
     * @param retailFloor     prix plancher au détail ; égal au prix de vente si absent
     * @param initialStock    quantité en stock, en unités de base
     * @param unitCost        coût d'achat d'une unité de base, pour valoriser le stock initial
     * @param packagingName   conditionnement de gros (carton, paquet…), avec packagingFactor
     * @param packagingFactor nombre d'unités de base dans ce conditionnement
     */
    public record ImportRow(
            String name,
            String category,
            String baseUnit,
            Long retailPrice,
            Long retailFloor,
            String barcode,
            Long alertThreshold,
            Long initialStock,
            Long unitCost,
            String packagingName,
            Long packagingFactor,
            Long packagingPrice,
            Long packagingFloor) {
    }

    /** @param dryRun vrai : vérifier sans rien enregistrer */
    public record ImportRequest(boolean dryRun, List<ImportRow> rows) {
    }

    /** @param line numéro de la ligne dans la demande, à partir de 1 */
    public record RowResult(int line, String name, String status, String message) {
    }

    public record ImportResult(boolean dryRun, int created, int skipped, int errors, List<RowResult> rows) {
    }

    public ImportResult run(ImportRequest request) {
        List<ImportRow> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new BusinessException("Aucune ligne à importer.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException("Trop de lignes : " + rows.size() + ". Importez au plus " + MAX_ROWS
                    + " produits à la fois.");
        }

        List<RowResult> results = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> seenBarcodes = new HashSet<>();
        int created = 0;
        int skipped = 0;
        int errors = 0;

        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            int line = i + 1;
            String name = row.name() == null ? "" : row.name().trim();
            RowResult result;
            try {
                if (name.isEmpty()) {
                    throw new BusinessException("Le nom du produit est vide.");
                }
                // En vérification, rien n'est enregistré : les doublons du tableau se repèrent ici.
                if (!seenNames.add(name.toLowerCase())) {
                    throw new BusinessException("Ce produit figure deux fois dans le tableau.");
                }
                String barcode = blankToNull(row.barcode());
                if (barcode != null && !seenBarcodes.add(barcode)) {
                    throw new BusinessException("Le code-barres " + barcode + " figure deux fois dans le tableau.");
                }
                if (exists(name)) {
                    result = new RowResult(line, name, SKIPPED, "Un produit de ce nom existe déjà : ligne ignorée.");
                    skipped++;
                } else {
                    String message = transaction.execute(status -> {
                        String done = importRow(row, name, barcode);
                        if (request.dryRun()) {
                            status.setRollbackOnly();
                        }
                        return done;
                    });
                    result = new RowResult(line, name, CREATED, message);
                    created++;
                }
            } catch (BusinessException e) {
                result = new RowResult(line, name, ERROR, e.getMessage());
                errors++;
            } catch (ArithmeticException e) {
                result = new RowResult(line, name, ERROR, "Un nombre de cette ligne est trop grand.");
                errors++;
            }
            results.add(result);
        }

        if (!request.dryRun() && created > 0) {
            audit.log("CATALOGUE_IMPORTE", created + " produit(s) créé(s) par import");
        }
        return new ImportResult(request.dryRun(), created, skipped, errors, results);
    }

    /** Crée le produit d'une ligne, et son stock initial s'il y en a un. Renvoie un résumé lisible. */
    private String importRow(ImportRow row, String name, String barcode) {
        String baseUnit = Optional.ofNullable(blankToNull(row.baseUnit())).orElse("pièce");
        requireNotNegative(row.retailPrice(), "Le prix de vente");
        requireNotNegative(row.retailFloor(), "Le prix plancher");
        requireNotNegative(row.alertThreshold(), "Le seuil d'alerte");
        requireNotNegative(row.initialStock(), "Le stock initial");
        requireNotNegative(row.unitCost(), "Le coût d'achat");
        requireNotNegative(row.packagingPrice(), "Le prix du conditionnement");
        requireNotNegative(row.packagingFloor(), "Le prix plancher du conditionnement");

        List<PackagingRequest> packagings = new ArrayList<>();
        packagings.add(new PackagingRequest(baseUnit, 1, barcode,
                prices(PriceLevel.DETAIL, row.retailPrice(), row.retailFloor())));

        String packagingName = blankToNull(row.packagingName());
        if (packagingName != null || row.packagingFactor() != null) {
            if (packagingName == null) {
                throw new BusinessException("Le conditionnement a un contenu mais pas de nom.");
            }
            if (row.packagingFactor() == null || row.packagingFactor() < 2) {
                throw new BusinessException("Le conditionnement « " + packagingName
                        + " » doit contenir au moins 2 unités de base.");
            }
            packagings.add(new PackagingRequest(packagingName, row.packagingFactor(), null,
                    prices(PriceLevel.GROS, row.packagingPrice(), row.packagingFloor())));
        } else if (row.packagingPrice() != null) {
            throw new BusinessException("Un prix de conditionnement est donné sans nom ni contenu.");
        }

        String categoryId = Optional.ofNullable(blankToNull(row.category())).map(this::findOrCreateCategory)
                .orElse(null);
        long threshold = row.alertThreshold() == null ? 0 : row.alertThreshold();
        ProductDetail product = catalog.createProduct(
                new ProductRequest(name, categoryId, baseUnit, threshold, packagings));

        StringBuilder summary = new StringBuilder("Produit créé");
        if (packagings.size() > 1) {
            summary.append(" avec le conditionnement « ").append(packagingName).append(" »");
        }
        if (row.initialStock() != null && row.initialStock() > 0) {
            PackagingView base = product.packagings().get(0);
            long unitCost = row.unitCost() == null ? 0 : row.unitCost();
            stock.receive(new ReceptionRequest(product.product().id(), base.id(), row.initialStock(),
                    Math.multiplyExact(row.initialStock(), unitCost), "Stock initial (import)"));
            summary.append(", stock initial de ").append(row.initialStock()).append(' ').append(baseUnit);
        }
        return summary.append('.').toString();
    }

    private static List<PriceRequest> prices(PriceLevel level, Long price, Long floor) {
        if (price == null) {
            if (floor != null) {
                throw new BusinessException("Un prix plancher est donné sans prix de vente.");
            }
            return List.of();
        }
        return List.of(new PriceRequest(level, price, floor == null ? price : floor));
    }

    private static void requireNotNegative(Long value, String label) {
        if (value != null && value < 0) {
            throw new BusinessException(label + " ne peut pas être négatif.");
        }
    }

    private boolean exists(String name) {
        return jdbc.sql("SELECT COUNT(*) FROM product WHERE name = ? COLLATE NOCASE")
                .param(name).query(Long.class).single() > 0;
    }

    private String findOrCreateCategory(String name) {
        Optional<String> existing = jdbc.sql("SELECT id FROM category WHERE name = ? COLLATE NOCASE")
                .param(name).query(String.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO category (id, name, created_at) VALUES (?, ?, ?)")
                .params(id, name, Timestamps.now())
                .update();
        return id;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
