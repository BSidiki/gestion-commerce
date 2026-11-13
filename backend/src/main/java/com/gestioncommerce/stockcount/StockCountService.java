package com.gestioncommerce.stockcount;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.CurrentUser;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.common.Timestamps;
import com.gestioncommerce.inventory.StockDtos.AdjustmentReason;
import com.gestioncommerce.inventory.StockDtos.AdjustmentRequest;
import com.gestioncommerce.inventory.StockService;

/**
 * Inventaire physique. On compte les produits un à un ; chaque comptage retient le stock du
 * logiciel à cet instant. À la validation, l'écart de chaque produit est appliqué au stock
 * courant : une vente faite entre-temps reste prise en compte.
 */
@Service
public class StockCountService {

    public static final String IN_PROGRESS = "EN_COURS";
    public static final String VALIDATED = "VALIDE";
    public static final String ABANDONED = "ABANDONNE";

    private final JdbcClient jdbc;
    private final StockService stock;
    private final AuditService audit;

    public StockCountService(JdbcClient jdbc, StockService stock, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.audit = audit;
    }

    /**
     * @param difference      countedQty - expectedQty, en unités de base
     * @param valueDifference valeur de l'écart au coût moyen, en francs CFA (négative : marchandise manquante)
     */
    public record CountLine(String productId, String productName, String baseUnit, long expectedQty,
            long countedQty, long difference, long valueDifference, String countedAt) {
    }

    /**
     * @param lineCount       nombre de produits comptés
     * @param differenceCount nombre de produits dont le compte diffère du logiciel
     * @param missingValue    valeur de la marchandise manquante, en francs CFA (positive)
     * @param surplusValue    valeur de la marchandise en trop, en francs CFA
     */
    public record CountSummary(String id, long number, String label, String status, String startedAt,
            String startedBy, String closedAt, String closedBy, long lineCount, long differenceCount,
            long missingValue, long surplusValue) {
    }

    public record CountDetail(CountSummary count, List<CountLine> lines) {
    }

    public record StartRequest(String label) {
    }

    public record LineRequest(long countedQty) {
    }

    /** Les 30 derniers inventaires, le plus récent en premier. */
    @Transactional
    public List<CountSummary> list() {
        return jdbc.sql("SELECT id FROM stock_count ORDER BY number DESC LIMIT 30")
                .query(String.class)
                .list()
                .stream()
                .map(id -> get(id).count())
                .toList();
    }

    @Transactional
    public CountDetail start(StartRequest request) {
        boolean running = jdbc.sql("SELECT COUNT(*) FROM stock_count WHERE status = 'EN_COURS'")
                .query(Long.class).single() > 0;
        if (running) {
            throw new BusinessException("Un inventaire est déjà en cours : validez-le ou abandonnez-le d'abord.");
        }
        String id = UUID.randomUUID().toString();
        long number = jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM stock_count").query(Long.class).single();
        String label = request == null || request.label() == null || request.label().isBlank()
                ? null
                : request.label().trim();
        jdbc.sql("INSERT INTO stock_count (id, number, label, started_at, started_by) VALUES (?, ?, ?, ?, ?)")
                .params(id, number, label, Timestamps.now(), CurrentUser.username())
                .update();
        return get(id);
    }

    @Transactional
    public CountDetail get(String countId) {
        List<CountLine> lines = jdbc.sql("""
                SELECT l.product_id, p.name, p.base_unit, l.expected_qty, l.counted_qty, l.cost_milli, l.counted_at
                FROM stock_count_line l
                JOIN product p ON p.id = l.product_id
                WHERE l.count_id = ?
                ORDER BY l.counted_at DESC, l.rowid DESC
                """)
                .param(countId)
                .query((rs, i) -> {
                    long difference = rs.getLong("counted_qty") - rs.getLong("expected_qty");
                    return new CountLine(rs.getString("product_id"), rs.getString("name"),
                            rs.getString("base_unit"), rs.getLong("expected_qty"), rs.getLong("counted_qty"),
                            difference, Math.round(difference * rs.getLong("cost_milli") / 1000.0),
                            rs.getString("counted_at"));
                })
                .list();
        long missing = 0;
        long surplus = 0;
        long differences = 0;
        for (CountLine line : lines) {
            if (line.difference() != 0) {
                differences++;
            }
            if (line.valueDifference() < 0) {
                missing -= line.valueDifference();
            } else {
                surplus += line.valueDifference();
            }
        }
        long lineCount = lines.size();
        long differenceCount = differences;
        long missingValue = missing;
        long surplusValue = surplus;
        CountSummary summary = jdbc.sql("""
                SELECT id, number, label, status, started_at, started_by, closed_at, closed_by
                FROM stock_count
                WHERE id = ?
                """)
                .param(countId)
                .query((rs, i) -> new CountSummary(rs.getString("id"), rs.getLong("number"),
                        rs.getString("label"), rs.getString("status"), rs.getString("started_at"),
                        rs.getString("started_by"), rs.getString("closed_at"), rs.getString("closed_by"),
                        lineCount, differenceCount, missingValue, surplusValue))
                .optional()
                .orElseThrow(() -> new NotFoundException("Inventaire introuvable."));
        return new CountDetail(summary, lines);
    }

    /** Enregistre ou remplace le comptage d'un produit, et retient le stock du logiciel à cet instant. */
    @Transactional
    public CountDetail count(String countId, String productId, LineRequest request) {
        requireInProgress(countId);
        if (request.countedQty() < 0) {
            throw new BusinessException("La quantité comptée ne peut pas être négative.");
        }
        long[] level = jdbc.sql("SELECT stock_qty, avg_cost_milli FROM product WHERE id = ?")
                .param(productId)
                .query((rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) })
                .optional()
                .orElseThrow(() -> new NotFoundException("Produit introuvable."));
        jdbc.sql("""
                INSERT INTO stock_count_line (id, count_id, product_id, expected_qty, counted_qty, cost_milli,
                                              counted_at, counted_by)
                VALUES (:id, :countId, :productId, :expected, :counted, :cost, :countedAt, :countedBy)
                ON CONFLICT (count_id, product_id) DO UPDATE SET
                    expected_qty = excluded.expected_qty,
                    counted_qty = excluded.counted_qty,
                    cost_milli = excluded.cost_milli,
                    counted_at = excluded.counted_at,
                    counted_by = excluded.counted_by
                """)
                .param("id", UUID.randomUUID().toString())
                .param("countId", countId)
                .param("productId", productId)
                .param("expected", level[0])
                .param("counted", request.countedQty())
                .param("cost", level[1])
                .param("countedAt", Timestamps.now())
                .param("countedBy", CurrentUser.username())
                .update();
        return get(countId);
    }

    @Transactional
    public CountDetail removeLine(String countId, String productId) {
        requireInProgress(countId);
        jdbc.sql("DELETE FROM stock_count_line WHERE count_id = ? AND product_id = ?")
                .params(countId, productId)
                .update();
        return get(countId);
    }

    /** Applique les écarts au stock. Les produits non comptés ne sont pas touchés. */
    @Transactional
    public CountDetail validate(String countId) {
        CountSummary count = requireInProgress(countId);
        CountDetail detail = get(countId);
        if (detail.lines().isEmpty()) {
            throw new BusinessException("Aucun produit n'a été compté : rien à valider.");
        }
        String note = "Inventaire n° " + count.number() + (count.label() == null ? "" : " — " + count.label());
        for (CountLine line : detail.lines()) {
            if (line.difference() != 0) {
                stock.adjust(new AdjustmentRequest(line.productId(), line.difference(),
                        AdjustmentReason.INVENTAIRE, note));
            }
        }
        close(countId, VALIDATED);
        audit.log("INVENTAIRE_VALIDE", note + " : " + detail.count().lineCount() + " produit(s) compté(s), "
                + detail.count().differenceCount() + " écart(s), manquant " + detail.count().missingValue()
                + " F, en trop " + detail.count().surplusValue() + " F");
        return get(countId);
    }

    /** Arrête l'inventaire sans rien corriger. Les comptages restent consultables. */
    @Transactional
    public CountDetail abandon(String countId) {
        CountSummary count = requireInProgress(countId);
        close(countId, ABANDONED);
        audit.log("INVENTAIRE_ABANDONNE", "Inventaire n° " + count.number());
        return get(countId);
    }

    private void close(String countId, String status) {
        jdbc.sql("UPDATE stock_count SET status = ?, closed_at = ?, closed_by = ? WHERE id = ?")
                .params(status, Timestamps.now(), CurrentUser.username(), countId)
                .update();
    }

    private CountSummary requireInProgress(String countId) {
        CountSummary count = get(countId).count();
        if (!IN_PROGRESS.equals(count.status())) {
            throw new BusinessException("Cet inventaire est terminé : il ne peut plus être modifié.");
        }
        return count;
    }
}
