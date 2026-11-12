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

import com.gestioncommerce.catalog.CatalogDtos.CategoryRequest;
import com.gestioncommerce.catalog.CatalogDtos.CategoryView;
import com.gestioncommerce.catalog.CatalogDtos.PackagingRequest;
import com.gestioncommerce.catalog.CatalogDtos.PackagingView;
import com.gestioncommerce.catalog.CatalogDtos.PriceRequest;
import com.gestioncommerce.catalog.CatalogDtos.PriceView;
import com.gestioncommerce.catalog.CatalogDtos.ProductDetail;
import com.gestioncommerce.catalog.CatalogDtos.ProductRequest;
import com.gestioncommerce.catalog.CatalogDtos.ProductSummary;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.common.Timestamps;

@Service
public class CatalogService {

    private static final String PRODUCT_COLUMNS =
            "id, name, category_id, base_unit, stock_qty, avg_cost_milli, alert_threshold";

    private final JdbcClient jdbc;

    public CatalogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    @Transactional
    public List<ProductSummary> listProducts(boolean lowStockOnly) {
        String sql = "SELECT " + PRODUCT_COLUMNS + " FROM product WHERE active = 1"
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
        if (request.categoryId() != null) {
            boolean categoryExists = jdbc.sql("SELECT COUNT(*) FROM category WHERE id = ?")
                    .param(request.categoryId()).query(Long.class).single() > 0;
            if (!categoryExists) {
                throw new BusinessException("Catégorie inconnue.");
            }
        }

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
        if (packaging.barcode() == null || packaging.barcode().isBlank()) {
            return;
        }
        boolean used = jdbc.sql("SELECT COUNT(*) FROM product_barcode WHERE code = ?")
                .param(packaging.barcode().trim()).query(Long.class).single() > 0;
        if (used) {
            throw new BusinessException(
                    "Le code-barres " + packaging.barcode().trim() + " est déjà utilisé par un autre produit.");
        }
    }

    private void validatePrices(PackagingRequest packaging) {
        if (packaging.prices() == null) {
            return;
        }
        Set<PriceLevel> levels = new HashSet<>();
        for (PriceRequest price : packaging.prices()) {
            if (!levels.add(price.level())) {
                throw new BusinessException("Le conditionnement « " + packaging.name().trim()
                        + " » a deux prix pour le niveau " + price.level() + ".");
            }
            if (price.floorPrice() > price.price()) {
                throw new BusinessException("Le prix plancher ne peut pas dépasser le prix de vente ("
                        + packaging.name().trim() + ", " + price.level() + ").");
            }
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
                rs.getString("base_unit"), stock, rs.getLong("avg_cost_milli"), threshold, stock <= threshold);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return Timestamps.now();
    }
}
