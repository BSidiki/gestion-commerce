package com.gestioncommerce.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.catalog.CatalogDtos.CategoryRequest;
import com.gestioncommerce.catalog.CatalogDtos.CategoryView;
import com.gestioncommerce.catalog.CatalogDtos.PackagingRequest;
import com.gestioncommerce.catalog.CatalogDtos.PackagingUpdateRequest;
import com.gestioncommerce.catalog.CatalogDtos.PackagingView;
import com.gestioncommerce.catalog.CatalogDtos.PriceRequest;
import com.gestioncommerce.catalog.CatalogDtos.PriceView;
import com.gestioncommerce.catalog.CatalogDtos.ProductDetail;
import com.gestioncommerce.catalog.CatalogDtos.ProductRequest;
import com.gestioncommerce.catalog.CatalogDtos.ProductSummary;
import com.gestioncommerce.catalog.CatalogDtos.ProductUpdateRequest;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.common.Timestamps;

@Service
public class CatalogService {

    private static final String PRODUCT_COLUMNS =
            "id, name, category_id, base_unit, stock_qty, avg_cost_milli, alert_threshold, active";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CatalogService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------- Catégories ----------

    @Transactional
    public CategoryView createCategory(CategoryRequest request) {
        String name = request.name().trim();
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM category WHERE name = ?")
                .param(name).query(Long.class).single() > 0;
        if (exists) {
            throw new BusinessException("La catégorie « " + name + " » existe déjà.");
        }
        String id = newId();
        jdbc.sql("INSERT INTO category (id, name, created_at) VALUES (?, ?, ?)")
                .params(id, name, now()).update();
        return new CategoryView(id, name);
    }

    @Transactional
    public List<CategoryView> listCategories() {
        return jdbc.sql("SELECT id, name FROM category ORDER BY name")
                .query((rs, i) -> new CategoryView(rs.getString("id"), rs.getString("name")))
                .list();
    }

    // ---------- Produits ----------

    @Transactional
    public ProductDetail createProduct(ProductRequest request) {
        validate(request);

        String productId = newId();
        String now = now();
        jdbc.sql("""
                INSERT INTO product (id, name, category_id, base_unit, alert_threshold, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .params(productId, request.name().trim(), request.categoryId(), request.baseUnit().trim(),
                        request.alertThreshold(), now, now)
                .update();

        for (PackagingRequest packaging : request.packagings()) {
            String packagingId = newId();
            jdbc.sql("INSERT INTO product_packaging (id, product_id, name, factor) VALUES (?, ?, ?, ?)")
                    .params(packagingId, productId, packaging.name().trim(), packaging.factor())
                    .update();

            if (packaging.barcode() != null && !packaging.barcode().isBlank()) {
                jdbc.sql("INSERT INTO product_barcode (id, packaging_id, code) VALUES (?, ?, ?)")
                        .params(newId(), packagingId, packaging.barcode().trim())
                        .update();
            }
            if (packaging.prices() != null) {
                for (PriceRequest price : packaging.prices()) {
                    jdbc.sql("""
                            INSERT INTO product_price (id, packaging_id, level, price, floor_price)
                            VALUES (?, ?, ?, ?, ?)
                            """)
                            .params(newId(), packagingId, price.level().name(), price.price(), price.floorPrice())
                            .update();
                }
            }
        }
        return getProduct(productId);
    }

    /**
     * Modifie un produit. Les conditionnements existants gardent leur contenu (leur facteur) :
     * le changer fausserait le stock déjà compté. Aucun conditionnement n'est supprimé.
     */
    @Transactional
    public ProductDetail updateProduct(String productId, ProductUpdateRequest request) {
        ProductDetail before = getProduct(productId);
        requireCategory(request.categoryId());

        Map<String, PackagingView> existing = new HashMap<>();
        for (PackagingView packaging : before.packagings()) {
            existing.put(packaging.id(), packaging);
        }
        List<PackagingUpdateRequest> changes = request.packagings() == null ? List.of() : request.packagings();

        // Noms : ceux des conditionnements laissés tels quels, puis ceux de la demande, sans doublon.
        Set<String> touched = new HashSet<>();
        for (PackagingUpdateRequest change : changes) {
            if (change.id() != null) {
                touched.add(change.id());
            }
        }
        Set<String> barcodes = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (PackagingView packaging : before.packagings()) {
            if (!touched.contains(packaging.id())) {
                names.add(packaging.name().trim().toLowerCase());
            }
        }
        for (PackagingUpdateRequest change : changes) {
            String name = change.name().trim();
            if (change.id() != null) {
                PackagingView current = existing.get(change.id());
                if (current == null) {
                    throw new BusinessException("Le conditionnement « " + name + " » n'appartient pas à ce produit.");
                }
                if (current.factor() != change.factor()) {
                    throw new BusinessException("Le contenu du conditionnement « " + current.name()
                            + " » ne peut pas être modifié : le stock déjà compté deviendrait faux. "
                            + "Ajoutez plutôt un nouveau conditionnement.");
                }
            } else if (change.factor() < 2) {
                throw new BusinessException("Un nouveau conditionnement doit contenir au moins 2 unités de base : "
                        + "l'unité de base existe déjà.");
            }
            if (!names.add(name.toLowerCase())) {
                throw new BusinessException("Le conditionnement « " + name + " » est défini deux fois.");
            }
            if (change.barcode() != null && !change.barcode().isBlank() && !barcodes.add(change.barcode().trim())) {
                throw new BusinessException("Le code-barres " + change.barcode().trim()
                        + " est saisi pour deux conditionnements.");
            }
            requireBarcodeFree(change.barcode(), change.id());
            requireValidPrices(name, change.prices());
        }

        jdbc.sql("""
                UPDATE product
                SET name = :name, category_id = :categoryId, base_unit = :baseUnit,
                    alert_threshold = :alertThreshold, updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("name", request.name().trim())
                .param("categoryId", request.categoryId())
                .param("baseUnit", request.baseUnit().trim())
                .param("alertThreshold", request.alertThreshold())
                .param("updatedAt", now())
                .param("id", productId)
                .update();

        List<String> priceChanges = new ArrayList<>();
        for (PackagingUpdateRequest change : changes) {
            String packagingId = change.id();
            if (packagingId == null) {
                packagingId = newId();
                jdbc.sql("INSERT INTO product_packaging (id, product_id, name, factor) VALUES (?, ?, ?, ?)")
                        .params(packagingId, productId, change.name().trim(), change.factor())
                        .update();
            } else {
                jdbc.sql("UPDATE product_packaging SET name = ? WHERE id = ?")
                        .params(change.name().trim(), packagingId)
                        .update();
                describePriceChanges(existing.get(packagingId), change, priceChanges);
                jdbc.sql("DELETE FROM product_barcode WHERE packaging_id = ?").param(packagingId).update();
                jdbc.sql("DELETE FROM product_price WHERE packaging_id = ?").param(packagingId).update();
            }
            if (change.barcode() != null && !change.barcode().isBlank()) {
                jdbc.sql("INSERT INTO product_barcode (id, packaging_id, code) VALUES (?, ?, ?)")
                        .params(newId(), packagingId, change.barcode().trim())
                        .update();
            }
            if (change.prices() != null) {
                for (PriceRequest price : change.prices()) {
                    jdbc.sql("""
                            INSERT INTO product_price (id, packaging_id, level, price, floor_price)
                            VALUES (?, ?, ?, ?, ?)
                            """)
                            .params(newId(), packagingId, price.level().name(), price.price(), price.floorPrice())
                            .update();
                }
            }
        }

        if (priceChanges.isEmpty()) {
            audit.log("PRODUIT_MODIFIE", "« " + request.name().trim() + " »");
        } else {
            audit.log("PRIX_MODIFIE", "« " + request.name().trim() + " » : " + String.join(" ; ", priceChanges));
        }
        return getProduct(productId);
    }

    /** Un produit désactivé disparaît de la caisse et des listes ; son historique est conservé. */
    @Transactional
    public ProductDetail setActive(String productId, boolean active) {
        ProductDetail product = getProduct(productId);
        jdbc.sql("UPDATE product SET active = ?, updated_at = ? WHERE id = ?")
                .params(active ? 1 : 0, now(), productId)
                .update();
        audit.log(active ? "PRODUIT_REACTIVE" : "PRODUIT_DESACTIVE", "« " + product.product().name() + " »");
        return getProduct(productId);
    }

    @Transactional
    public List<ProductSummary> listProducts(boolean lowStockOnly) {
        return listProducts(lowStockOnly, false);
    }

    @Transactional
    public List<ProductSummary> listProducts(boolean lowStockOnly, boolean includeInactive) {
        String sql = "SELECT " + PRODUCT_COLUMNS + " FROM product WHERE 1 = 1"
                + (includeInactive ? "" : " AND active = 1")
                + (lowStockOnly ? " AND stock_qty <= alert_threshold" : "")
                + " ORDER BY name";
        return jdbc.sql(sql).query((rs, i) -> mapSummary(rs)).list();
    }

    @Transactional
    public ProductDetail getProduct(String productId) {
        ProductSummary summary = jdbc.sql("SELECT " + PRODUCT_COLUMNS + " FROM product WHERE id = ?")
                .param(productId)
                .query((rs, i) -> mapSummary(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("Produit introuvable."));

        List<PackagingView> packagings = jdbc.sql(
                        "SELECT id, name, factor FROM product_packaging WHERE product_id = ? ORDER BY factor")
                .param(productId)
                .query((rs, i) -> {
                    String packagingId = rs.getString("id");
                    return new PackagingView(packagingId, rs.getString("name"), rs.getLong("factor"),
                            barcodesOf(packagingId), pricesOf(packagingId));
                })
                .list();
        return new ProductDetail(summary, packagings);
    }

    private record PriceRow(String packagingId, PriceView price) {
    }

    private record PackagingRow(String productId, PackagingView packaging) {
    }

    /**
     * Tout le catalogue vendable en une fois, pour l'écran de caisse : la recherche
     * par nom ou code-barres se fait ensuite dans l'interface, sans aller-retour.
     */
    @Transactional
    public List<ProductDetail> listSellable() {
        Map<String, List<String>> barcodes = new HashMap<>();
        jdbc.sql("SELECT packaging_id, code FROM product_barcode ORDER BY code")
                .query((rs, i) -> Map.entry(rs.getString("packaging_id"), rs.getString("code")))
                .list()
                .forEach(e -> barcodes.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

        Map<String, List<PriceView>> prices = new HashMap<>();
        jdbc.sql("SELECT packaging_id, level, price, floor_price FROM product_price ORDER BY price DESC")
                .query((rs, i) -> new PriceRow(rs.getString("packaging_id"),
                        new PriceView(PriceLevel.valueOf(rs.getString("level")),
                                rs.getLong("price"), rs.getLong("floor_price"))))
                .list()
                .forEach(row -> prices.computeIfAbsent(row.packagingId(), k -> new ArrayList<>()).add(row.price()));

        Map<String, List<PackagingView>> packagings = new HashMap<>();
        jdbc.sql("SELECT id, product_id, name, factor FROM product_packaging ORDER BY factor")
                .query((rs, i) -> {
                    String packagingId = rs.getString("id");
                    return new PackagingRow(rs.getString("product_id"),
                            new PackagingView(packagingId, rs.getString("name"), rs.getLong("factor"),
                                    barcodes.getOrDefault(packagingId, List.of()),
                                    prices.getOrDefault(packagingId, List.of())));
                })
                .list()
                .forEach(row -> packagings.computeIfAbsent(row.productId(), k -> new ArrayList<>())
                        .add(row.packaging()));

        return listProducts(false).stream()
                .map(product -> new ProductDetail(product, packagings.getOrDefault(product.id(), List.of())))
                .toList();
    }

    // ---------- Règles de validation ----------

    private void validate(ProductRequest request) {
        requireCategory(request.categoryId());

        // Le stock se compte en unité de base : il faut un conditionnement de facteur 1.
        boolean hasBaseUnit = request.packagings().stream().anyMatch(p -> p.factor() == 1);
        if (!hasBaseUnit) {
            throw new BusinessException(
                    "Le produit doit avoir un conditionnement de facteur 1 (l'unité de base).");
        }

        Set<String> names = new HashSet<>();
        for (PackagingRequest packaging : request.packagings()) {
            String name = packaging.name().trim().toLowerCase();
            if (!names.add(name)) {
                throw new BusinessException(
                        "Le conditionnement « " + packaging.name().trim() + " » est défini deux fois.");
            }
            validateBarcode(packaging);
            validatePrices(packaging);
        }
    }

    private void validateBarcode(PackagingRequest packaging) {
        requireBarcodeFree(packaging.barcode(), null);
    }

    private void validatePrices(PackagingRequest packaging) {
        requireValidPrices(packaging.name().trim(), packaging.prices());
    }

    private void requireCategory(String categoryId) {
        if (categoryId == null) {
            return;
        }
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM category WHERE id = ?")
                .param(categoryId).query(Long.class).single() > 0;
        if (!exists) {
            throw new BusinessException("Catégorie inconnue.");
        }
    }

    /** Un code-barres ne désigne qu'un conditionnement. exceptPackagingId : celui qui le porte déjà. */
    private void requireBarcodeFree(String barcode, String exceptPackagingId) {
        if (barcode == null || barcode.isBlank()) {
            return;
        }
        boolean used = jdbc.sql("SELECT COUNT(*) FROM product_barcode WHERE code = ? AND packaging_id <> ?")
                .params(barcode.trim(), exceptPackagingId == null ? "" : exceptPackagingId)
                .query(Long.class).single() > 0;
        if (used) {
            throw new BusinessException("Le code-barres " + barcode.trim() + " est déjà utilisé par un autre produit.");
        }
    }

    private static void requireValidPrices(String packagingName, List<PriceRequest> prices) {
        if (prices == null) {
            return;
        }
        Set<PriceLevel> levels = new HashSet<>();
        for (PriceRequest price : prices) {
            if (!levels.add(price.level())) {
                throw new BusinessException("Le conditionnement « " + packagingName
                        + " » a deux prix pour le niveau " + price.level() + ".");
            }
            if (price.floorPrice() > price.price()) {
                throw new BusinessException("Le prix plancher ne peut pas dépasser le prix de vente ("
                        + packagingName + ", " + price.level() + ").");
            }
        }
    }

    /** Pour le journal : « Carton GROS 6500 → 6800 F », « Carton DEMI_GROS ajouté à 6600 F », « Unité GROS supprimé ». */
    private static void describePriceChanges(PackagingView before, PackagingUpdateRequest after, List<String> out) {
        Map<PriceLevel, PriceView> old = new HashMap<>();
        for (PriceView price : before.prices()) {
            old.put(price.level(), price);
        }
        List<PriceRequest> next = after.prices() == null ? List.of() : after.prices();
        for (PriceRequest price : next) {
            PriceView previous = old.remove(price.level());
            String label = before.name() + " " + price.level();
            if (previous == null) {
                out.add(label + " ajouté à " + price.price() + " F");
            } else if (previous.price() != price.price() || previous.floorPrice() != price.floorPrice()) {
                out.add(label + " " + previous.price() + " → " + price.price() + " F (plancher "
                        + previous.floorPrice() + " → " + price.floorPrice() + " F)");
            }
        }
        for (PriceLevel removed : old.keySet()) {
            out.add(before.name() + " " + removed + " supprimé");
        }
    }

    // ---------- Lecture ----------

    private List<String> barcodesOf(String packagingId) {
        return jdbc.sql("SELECT code FROM product_barcode WHERE packaging_id = ? ORDER BY code")
                .param(packagingId).query(String.class).list();
    }

    private List<PriceView> pricesOf(String packagingId) {
        return jdbc.sql("SELECT level, price, floor_price FROM product_price WHERE packaging_id = ? ORDER BY price DESC")
                .param(packagingId)
                .query((rs, i) -> new PriceView(PriceLevel.valueOf(rs.getString("level")),
                        rs.getLong("price"), rs.getLong("floor_price")))
                .list();
    }

    private static ProductSummary mapSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        long stock = rs.getLong("stock_qty");
        long threshold = rs.getLong("alert_threshold");
        return new ProductSummary(rs.getString("id"), rs.getString("name"), rs.getString("category_id"),
                rs.getString("base_unit"), stock, rs.getLong("avg_cost_milli"), threshold, stock <= threshold,
                rs.getInt("active") == 1);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return Timestamps.now();
    }
}
