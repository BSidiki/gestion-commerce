package com.gestioncommerce.suppliers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.cash.CashService;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.CurrentUser;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.common.PaymentMethod;
import com.gestioncommerce.common.Timestamps;
import com.gestioncommerce.inventory.StockDtos.ReceptionRequest;
import com.gestioncommerce.inventory.StockService;
import com.gestioncommerce.suppliers.SupplierDtos.PurchaseItemRequest;
import com.gestioncommerce.suppliers.SupplierDtos.PurchaseItemView;
import com.gestioncommerce.suppliers.SupplierDtos.PurchaseRequest;
import com.gestioncommerce.suppliers.SupplierDtos.PurchaseView;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierDetail;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierEntry;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierPaymentRequest;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierRequest;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierView;

/**
 * Fournisseurs, achats et dettes envers eux.
 * Un achat fait entrer la marchandise en stock (au coût indiqué) et augmente ce qui est dû au
 * fournisseur ; un paiement le diminue. L'achat entier est enregistré, ou rien.
 */
@Service
public class SupplierService {

    private static final String SUPPLIER_SELECT = """
            SELECT s.id, s.name, s.phone, s.note,
                   (SELECT COALESCE(SUM(p.total), 0) FROM purchase p
                    WHERE p.supplier_id = s.id AND p.status = 'VALIDE')
                   - (SELECT COALESCE(SUM(sp.amount), 0) FROM supplier_payment sp WHERE sp.supplier_id = s.id)
                   AS balance
            FROM supplier s
            """;

    private final JdbcClient jdbc;
    private final StockService stock;
    private final CashService cash;
    private final AuditService audit;

    public SupplierService(JdbcClient jdbc, StockService stock, CashService cash, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.cash = cash;
        this.audit = audit;
    }

    /** Le conditionnement d'une ligne d'achat, avec son produit. */
    private record PackagingRow(String productId, String productName, String packagingName, long factor,
            boolean active) {
    }

    // ---------- Fournisseurs ----------

    @Transactional
    public SupplierView create(SupplierRequest request) {
        String name = request.name().trim();
        requireNameFree(name, null);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO supplier (id, name, phone, note, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(id, name, blankToNull(request.phone()), blankToNull(request.note()), Timestamps.now())
                .update();
        return view(id);
    }

    @Transactional
    public SupplierView update(String supplierId, SupplierRequest request) {
        view(supplierId);
        String name = request.name().trim();
        requireNameFree(name, supplierId);
        jdbc.sql("UPDATE supplier SET name = ?, phone = ?, note = ? WHERE id = ?")
                .params(name, blankToNull(request.phone()), blankToNull(request.note()), supplierId)
                .update();
        return view(supplierId);
    }

    public List<SupplierView> list() {
        return jdbc.sql(SUPPLIER_SELECT + " ORDER BY s.name COLLATE NOCASE")
                .query((rs, i) -> new SupplierView(rs.getString("id"), rs.getString("name"),
                        rs.getString("phone"), rs.getString("note"), rs.getLong("balance")))
                .list();
    }

    /** Le fournisseur et son historique, le plus récent en premier. */
    @Transactional
    public SupplierDetail get(String supplierId) {
        SupplierView supplier = view(supplierId);
        List<SupplierEntry> entries = jdbc.sql("""
                SELECT 'ACHAT' AS type, p.total AS amount, p.id AS purchase_id, p.number AS purchase_number,
                       NULL AS method, 0 AS from_drawer, p.note, p.created_at, 0 AS position,
                       p.status = 'ANNULE' AS cancelled
                FROM purchase p
                WHERE p.supplier_id = :id
                UNION ALL
                SELECT 'PAIEMENT', sp.amount, sp.purchase_id, p.number,
                       sp.method, sp.cash_session_id IS NOT NULL, sp.note, sp.created_at, 1, 0
                FROM supplier_payment sp
                LEFT JOIN purchase p ON p.id = sp.purchase_id
                WHERE sp.supplier_id = :id
                ORDER BY created_at DESC, position DESC
                """)
                .param("id", supplierId)
                .query((rs, i) -> {
                    long number = rs.getLong("purchase_number");
                    Long numberOrNull = rs.wasNull() ? null : number;
                    String method = rs.getString("method");
                    return new SupplierEntry(rs.getString("type"), rs.getLong("amount"),
                            rs.getString("purchase_id"), numberOrNull,
                            method == null ? null : PaymentMethod.valueOf(method),
                            rs.getInt("from_drawer") == 1, rs.getString("note"), rs.getString("created_at"),
                            rs.getInt("cancelled") == 1);
                })
                .list();
        return new SupplierDetail(supplier, entries);
    }

    // ---------- Achats ----------

    @Transactional
    public PurchaseView createPurchase(PurchaseRequest request) {
        SupplierView supplier = view(request.supplierId());

        long total = 0;
        List<PackagingRow> rows = new ArrayList<>();
        for (PurchaseItemRequest item : request.items()) {
            PackagingRow row = packaging(item.packagingId());
            if (!row.active()) {
                throw new BusinessException("« " + row.productName() + " » est désactivé : réactivez-le avant "
                        + "d'en recevoir.");
            }
            rows.add(row);
            total = Math.addExact(total, item.totalCost());
        }
        if (request.paidAmount() > total) {
            throw new BusinessException("Le montant payé (" + request.paidAmount()
                    + " F) dépasse le total de l'achat (" + total + " F).");
        }
        if (request.paidAmount() > 0 && request.paidMethod() == null) {
            throw new BusinessException("Indiquez le moyen de paiement.");
        }

        String id = UUID.randomUUID().toString();
        long number = jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM purchase").query(Long.class).single();
        String now = Timestamps.now();
        String today = LocalDate.now().toString();
        jdbc.sql("""
                INSERT INTO purchase (id, number, supplier_id, total, note, business_date, created_at, created_by)
                VALUES (:id, :number, :supplierId, :total, :note, :businessDate, :createdAt, :createdBy)
                """)
                .param("id", id)
                .param("number", number)
                .param("supplierId", supplier.id())
                .param("total", total)
                .param("note", blankToNull(request.note()))
                .param("businessDate", today)
                .param("createdAt", now)
                .param("createdBy", CurrentUser.username())
                .update();

        String label = "Achat n° " + number + " — " + supplier.name();
        for (int i = 0; i < rows.size(); i++) {
            PackagingRow row = rows.get(i);
            PurchaseItemRequest item = request.items().get(i);
            jdbc.sql("""
                    INSERT INTO purchase_item (id, purchase_id, product_id, packaging_id, product_name,
                                               packaging_name, factor, quantity, total_cost)
                    VALUES (:id, :purchaseId, :productId, :packagingId, :productName,
                            :packagingName, :factor, :quantity, :totalCost)
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("purchaseId", id)
                    .param("productId", row.productId())
                    .param("packagingId", item.packagingId())
                    .param("productName", row.productName())
                    .param("packagingName", row.packagingName())
                    .param("factor", row.factor())
                    .param("quantity", item.quantity())
                    .param("totalCost", item.totalCost())
                    .update();
            // L'entrée en stock met à jour la quantité et le coût moyen du produit.
            stock.receive(new ReceptionRequest(row.productId(), item.packagingId(), item.quantity(),
                    item.totalCost(), label));
        }

        if (request.paidAmount() > 0) {
            insertPayment(supplier.id(), id, request.paidAmount(), request.paidMethod(),
                    request.fromCashDrawer(), null);
        }
        audit.log("ACHAT_ENREGISTRE", label + " : " + total + " F, dont " + request.paidAmount() + " F payés");
        return getPurchase(id);
    }

    @Transactional
    public PurchaseView getPurchase(String purchaseId) {
        List<PurchaseItemView> items = jdbc.sql("""
                SELECT product_id, product_name, packaging_name, factor, quantity, total_cost
                FROM purchase_item
                WHERE purchase_id = ?
                ORDER BY rowid
                """)
                .param(purchaseId)
                .query((rs, i) -> new PurchaseItemView(rs.getString("product_id"), rs.getString("product_name"),
                        rs.getString("packaging_name"), rs.getLong("factor"), rs.getLong("quantity"),
                        rs.getLong("total_cost")))
                .list();
        return jdbc.sql("""
                SELECT p.id, p.number, p.supplier_id, s.name AS supplier_name, p.total, p.note,
                       p.business_date, p.created_at, p.status, p.cancel_reason,
                       (SELECT COALESCE(SUM(sp.amount), 0) FROM supplier_payment sp
                        WHERE sp.purchase_id = p.id) AS paid
                FROM purchase p
                JOIN supplier s ON s.id = p.supplier_id
                WHERE p.id = ?
                """)
                .param(purchaseId)
                .query((rs, i) -> new PurchaseView(rs.getString("id"), rs.getLong("number"),
                        rs.getString("supplier_id"), rs.getString("supplier_name"), rs.getLong("total"),
                        rs.getLong("paid"), rs.getString("note"), rs.getString("business_date"),
                        rs.getString("created_at"), rs.getString("status"), rs.getString("cancel_reason"), items))
                .optional()
                .orElseThrow(() -> new NotFoundException("Achat introuvable."));
    }

    /**
     * Annule un achat par des écritures inverses : la marchandise ressort du stock et l'achat ne
     * compte plus dans la dette. Refusé en entier si une partie de la marchandise a déjà été vendue.
     * Ce qui a été payé au fournisseur reste enregistré : il le doit à la boutique.
     */
    @Transactional
    public PurchaseView cancelPurchase(String purchaseId, String reason) {
        PurchaseView purchase = getPurchase(purchaseId);
        if (!"VALIDE".equals(purchase.status())) {
            throw new BusinessException("Cet achat est déjà annulé.");
        }
        String label = "Annulation de l'achat n° " + purchase.number() + " — " + purchase.supplierName();
        for (PurchaseItemView item : purchase.items()) {
            long baseQty = Math.multiplyExact(item.quantity(), item.factor());
            long inStock = jdbc.sql("SELECT stock_qty FROM product WHERE id = ?")
                    .param(item.productId()).query(Long.class).single();
            if (inStock < baseQty) {
                throw new BusinessException("« " + item.productName() + " » : il faudrait en retirer " + baseQty
                        + " du stock, mais il n'en reste que " + Math.max(inStock, 0)
                        + ". Une partie a déjà été vendue : l'achat ne peut plus être annulé.");
            }
            stock.reverseReception(item.productId(), baseQty, item.totalCost(), label);
        }
        jdbc.sql("UPDATE purchase SET status = 'ANNULE', cancelled_at = ?, cancel_reason = ? WHERE id = ?")
                .params(Timestamps.now(), reason.trim(), purchaseId)
                .update();
        audit.log("ACHAT_ANNULE", "Achat n° " + purchase.number() + " — " + purchase.supplierName() + " de "
                + purchase.total() + " F : " + reason.trim());
        return getPurchase(purchaseId);
    }

    // ---------- Paiements ----------

    @Transactional
    public SupplierView pay(String supplierId, SupplierPaymentRequest request) {
        SupplierView supplier = view(supplierId);
        if (request.amount() > supplier.balance()) {
            throw new BusinessException("Le paiement (" + request.amount() + " F) dépasse ce qui est dû à ce "
                    + "fournisseur (" + Math.max(supplier.balance(), 0) + " F).");
        }
        insertPayment(supplierId, null, request.amount(), request.method(), request.fromCashDrawer(),
                request.note());
        audit.log("FOURNISSEUR_PAYE", supplier.name() + " : " + request.amount() + " F");
        return view(supplierId);
    }

    private void insertPayment(String supplierId, String purchaseId, long amount, PaymentMethod method,
            boolean fromCashDrawer, String note) {
        String sessionId = null;
        if (fromCashDrawer) {
            if (method != PaymentMethod.ESPECES) {
                throw new BusinessException("Seul un paiement en espèces peut sortir de la caisse.");
            }
            sessionId = cash.currentSessionId().orElseThrow(() -> new BusinessException(
                    "Aucune caisse n'est ouverte : ouvrez-la, ou indiquez que l'argent ne vient pas de la caisse."));
        }
        jdbc.sql("""
                INSERT INTO supplier_payment (id, supplier_id, purchase_id, amount, method, cash_session_id,
                                              note, business_date, created_at, created_by)
                VALUES (:id, :supplierId, :purchaseId, :amount, :method, :sessionId,
                        :note, :businessDate, :createdAt, :createdBy)
                """)
                .param("id", UUID.randomUUID().toString())
                .param("supplierId", supplierId)
                .param("purchaseId", purchaseId)
                .param("amount", amount)
                .param("method", method.name())
                .param("sessionId", sessionId)
                .param("note", blankToNull(note))
                .param("businessDate", LocalDate.now().toString())
                .param("createdAt", Timestamps.now())
                .param("createdBy", CurrentUser.username())
                .update();
    }

    // ---------- Lectures internes ----------

    private SupplierView view(String supplierId) {
        return jdbc.sql(SUPPLIER_SELECT + " WHERE s.id = ?")
                .param(supplierId)
                .query((rs, i) -> new SupplierView(rs.getString("id"), rs.getString("name"),
                        rs.getString("phone"), rs.getString("note"), rs.getLong("balance")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Fournisseur introuvable."));
    }

    private PackagingRow packaging(String packagingId) {
        return jdbc.sql("""
                SELECT pp.product_id, p.name AS product_name, pp.name AS packaging_name, pp.factor, p.active
                FROM product_packaging pp
                JOIN product p ON p.id = pp.product_id
                WHERE pp.id = ?
                """)
                .param(packagingId)
                .query((rs, i) -> new PackagingRow(rs.getString("product_id"), rs.getString("product_name"),
                        rs.getString("packaging_name"), rs.getLong("factor"), rs.getInt("active") == 1))
                .optional()
                .orElseThrow(() -> new BusinessException("Un conditionnement de cet achat est introuvable."));
    }

    private void requireNameFree(String name, String exceptId) {
        boolean taken = jdbc.sql("SELECT COUNT(*) FROM supplier WHERE name = ? COLLATE NOCASE AND id <> ?")
                .params(name, exceptId == null ? "" : exceptId)
                .query(Long.class)
                .single() > 0;
        if (taken) {
            throw new BusinessException("Un fournisseur porte déjà ce nom.");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
