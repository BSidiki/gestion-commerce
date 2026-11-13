package com.gestioncommerce.inventory;

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
import com.gestioncommerce.inventory.StockDtos.AdjustmentRequest;
import com.gestioncommerce.inventory.StockDtos.MovementView;
import com.gestioncommerce.inventory.StockDtos.ReceptionRequest;
import com.gestioncommerce.inventory.StockDtos.StockLevel;

/**
 * Toute variation de stock passe par ce service : elle écrit un mouvement
 * historisé et met à jour le cache du produit dans la même transaction.
 */
@Service
public class StockService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public StockService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public StockLevel receive(ReceptionRequest request) {
        StockLevel current = currentLevel(request.productId());

        long factor = jdbc.sql("SELECT factor FROM product_packaging WHERE id = ? AND product_id = ?")
                .params(request.packagingId(), request.productId())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException("Ce conditionnement n'appartient pas au produit."));

        long baseQty = Math.multiplyExact(request.quantity(), factor);
        long unitCostMilli = Math.multiplyExact(request.totalCost(), 1000L) / baseQty;
        long newAvgCost = weightedAverageCost(current.stockQty(), current.avgCostMilli(), baseQty, unitCostMilli);
        long newStock = current.stockQty() + baseQty;

        insertMovement(request.productId(), "RECEPTION", baseQty, unitCostMilli, null, request.note(), newStock);
        updateProduct(request.productId(), newStock, newAvgCost);
        return new StockLevel(request.productId(), newStock, newAvgCost);
    }

    @Transactional
    public StockLevel adjust(AdjustmentRequest request) {
        if (request.quantityDelta() == 0) {
            throw new BusinessException("L'ajustement doit modifier le stock.");
        }
        StockLevel current = currentLevel(request.productId());
        long newStock = current.stockQty() + request.quantityDelta();

        // Un ajustement ne change pas le coût moyen : il corrige une quantité, pas une valeur.
        insertMovement(request.productId(), "AJUSTEMENT", request.quantityDelta(), null,
                request.reason().name(), request.note(), newStock);
        updateProduct(request.productId(), newStock, current.avgCostMilli());
        audit.log("STOCK_AJUSTE", "Produit " + request.productId() + " : " + request.quantityDelta() + " ("
                + request.reason().name() + ")");
        return new StockLevel(request.productId(), newStock, current.avgCostMilli());
    }

    /**
     * Sortie de stock d'une vente. Le stock peut devenir négatif : l'inventaire
     * initial est rarement exact, et la vente ne doit pas être bloquée pour autant.
     */
    @Transactional
    public StockLevel removeForSale(String productId, long baseQty, String note) {
        StockLevel current = currentLevel(productId);
        long newStock = current.stockQty() - baseQty;
        insertMovement(productId, "VENTE", -baseQty, current.avgCostMilli(), null, note, newStock);
        updateProduct(productId, newStock, current.avgCostMilli());
        return new StockLevel(productId, newStock, current.avgCostMilli());
    }

    /** Remise en stock lors de l'annulation d'une vente. Le coût moyen ne change pas. */
    @Transactional
    public StockLevel restoreForCancellation(String productId, long baseQty, String note) {
        StockLevel current = currentLevel(productId);
        long newStock = current.stockQty() + baseQty;
        insertMovement(productId, "ANNULATION", baseQty, current.avgCostMilli(), null, note, newStock);
        updateProduct(productId, newStock, current.avgCostMilli());
        return new StockLevel(productId, newStock, current.avgCostMilli());
    }

    @Transactional
    public List<MovementView> movementsOf(String productId) {
        currentLevel(productId);
        return jdbc.sql("""
                SELECT id, type, quantity, unit_cost_milli, reason, note, stock_after, created_at
                FROM stock_movement
                WHERE product_id = ?
                ORDER BY created_at DESC, rowid DESC
                """)
                .param(productId)
                .query((rs, i) -> {
                    long unitCost = rs.getLong("unit_cost_milli");
                    Long unitCostOrNull = rs.wasNull() || !CurrentUser.seesCosts() ? null : unitCost;
                    return new MovementView(rs.getString("id"), rs.getString("type"), rs.getLong("quantity"),
                            unitCostOrNull, rs.getString("reason"), rs.getString("note"),
                            rs.getLong("stock_after"), rs.getString("created_at"));
                })
                .list();
    }

    /**
     * Coût moyen pondéré après une entrée. Si le stock était nul ou négatif,
     * l'ancien coût ne porte sur aucune marchandise : on repart du coût reçu.
     */
    static long weightedAverageCost(long stockBefore, long avgCostBefore, long qtyIn, long unitCostIn) {
        if (stockBefore <= 0) {
            return unitCostIn;
        }
        long totalValue = Math.addExact(Math.multiplyExact(stockBefore, avgCostBefore),
                Math.multiplyExact(qtyIn, unitCostIn));
        return totalValue / (stockBefore + qtyIn);
    }

    private StockLevel currentLevel(String productId) {
        return jdbc.sql("SELECT stock_qty, avg_cost_milli FROM product WHERE id = ?")
                .param(productId)
                .query((rs, i) -> new StockLevel(productId, rs.getLong("stock_qty"), rs.getLong("avg_cost_milli")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Produit introuvable."));
    }

    /**
     * Défait une réception : la marchandise ressort du stock et le coût moyen est recalculé
     * comme si elle n'était jamais entrée. Refusé si le stock restant ne suffit pas.
     *
     * @param baseQty   quantité à retirer, en unités de base
     * @param totalCost coût total de la réception d'origine, en francs CFA
     */
    @Transactional
    public StockLevel reverseReception(String productId, long baseQty, long totalCost, String note) {
        StockLevel current = currentLevel(productId);
        if (current.stockQty() < baseQty) {
            throw new BusinessException("Le stock restant ne suffit pas pour défaire cette réception.");
        }
        long newStock = current.stockQty() - baseQty;
        long unitCostMilli = Math.multiplyExact(totalCost, 1000L) / baseQty;
        long newAvgCost = current.avgCostMilli();
        if (newStock > 0) {
            long value = Math.multiplyExact(current.stockQty(), current.avgCostMilli())
                    - Math.multiplyExact(baseQty, unitCostMilli);
            newAvgCost = Math.max(0, value / newStock);
        }
        insertMovement(productId, "ANNULATION", -baseQty, unitCostMilli, null, note, newStock);
        updateProduct(productId, newStock, newAvgCost);
        return new StockLevel(productId, newStock, newAvgCost);
    }

    private void insertMovement(String productId, String type, long quantity, Long unitCostMilli,
            String reason, String note, long stockAfter) {
        jdbc.sql("""
                INSERT INTO stock_movement
                    (id, product_id, type, quantity, unit_cost_milli, reason, note, stock_after, created_at,
                     created_by)
                VALUES (:id, :productId, :type, :quantity, :unitCost, :reason, :note, :stockAfter, :createdAt,
                        :createdBy)
                """)
                .param("createdBy", CurrentUser.username())
                .param("id", UUID.randomUUID().toString())
                .param("productId", productId)
                .param("type", type)
                .param("quantity", quantity)
                .param("unitCost", unitCostMilli)
                .param("reason", reason)
                .param("note", note)
                .param("stockAfter", stockAfter)
                .param("createdAt", Timestamps.now())
                .update();
    }

    private void updateProduct(String productId, long stockQty, long avgCostMilli) {
        jdbc.sql("UPDATE product SET stock_qty = ?, avg_cost_milli = ?, updated_at = ? WHERE id = ?")
                .params(stockQty, avgCostMilli, Timestamps.now(), productId)
                .update();
    }
}
