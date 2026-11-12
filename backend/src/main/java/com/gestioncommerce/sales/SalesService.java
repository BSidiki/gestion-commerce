package com.gestioncommerce.sales;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import com.gestioncommerce.customers.CustomerService;
import com.gestioncommerce.inventory.StockService;
import com.gestioncommerce.sales.SalesDtos.DailyReport;
import com.gestioncommerce.sales.SalesDtos.PaymentRequest;
import com.gestioncommerce.sales.SalesDtos.PaymentView;
import com.gestioncommerce.sales.SalesDtos.SaleItemRequest;
import com.gestioncommerce.sales.SalesDtos.SaleItemView;
import com.gestioncommerce.sales.SalesDtos.SaleRequest;
import com.gestioncommerce.sales.SalesDtos.SaleView;

/**
 * Une vente est atomique : la vente, ses lignes, ses paiements, son crédit
 * éventuel et ses sorties de stock s'écrivent dans une seule transaction.
 */
@Service
public class SalesService {

    private final JdbcClient jdbc;
    private final StockService stock;
    private final CustomerService customers;
    private final CashService cash;
    private final AuditService audit;

    public SalesService(JdbcClient jdbc, StockService stock, CustomerService customers, CashService cash,
            AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.customers = customers;
        this.cash = cash;
        this.audit = audit;
    }

    /** Ligne de vente résolue : la demande, complétée par ce que le catalogue en dit à cet instant. */
    private record Line(
            String packagingId,
            String productId,
            String productName,
            String packagingName,
            long factor,
            long quantity,
            long unitPrice,
            Long listPrice,
            long unitCostMilli,
            long lineTotal,
            boolean belowFloor) {
    }

    private record Catalog(
            String productId,
            String productName,
            String packagingName,
            long factor,
            long avgCostMilli,
            Long minFloorPrice,
            Long listPrice) {
    }

    @Transactional
    public SaleView create(SaleRequest request) {
        // Idempotence : la même validation renvoyée deux fois donne la même vente.
        Optional<String> existing = jdbc.sql("SELECT id FROM sale WHERE idempotency_key = ?")
                .param(request.idempotencyKey())
                .query(String.class)
                .optional();
        if (existing.isPresent()) {
            return get(existing.get());
        }

        if (request.customerId() != null) {
            customers.requireExists(request.customerId());
        }

        List<Line> lines = new ArrayList<>();
        long total = 0;
        for (SaleItemRequest item : request.items()) {
            Line line = resolve(item);
            lines.add(line);
            total = Math.addExact(total, line.lineTotal());
        }

        long paid = 0;
        List<PaymentRequest> payments = request.payments() == null ? List.of() : request.payments();
        for (PaymentRequest payment : payments) {
            paid = Math.addExact(paid, payment.amount());
        }
        if (paid > total) {
            throw new BusinessException("Le montant encaissé (" + paid + " F) dépasse le total de la vente ("
                    + total + " F). Rendez la monnaie avant de valider.");
        }
        long credit = total - paid;
        if (credit > 0 && request.customerId() == null) {
            throw new BusinessException("Il reste " + credit
                    + " F à payer : choisissez un client pour vendre à crédit.");
        }

        String saleId = UUID.randomUUID().toString();
        long number = jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM sale").query(Long.class).single();
        String now = Timestamps.now();
        String businessDate = LocalDate.now().toString();

        jdbc.sql("""
                INSERT INTO sale (id, number, idempotency_key, customer_id, total, paid, credit,
                                  business_date, created_at, created_by)
                VALUES (:id, :number, :key, :customerId, :total, :paid, :credit, :businessDate, :createdAt,
                        :createdBy)
                """)
                .param("createdBy", CurrentUser.username())
                .param("id", saleId)
                .param("number", number)
                .param("key", request.idempotencyKey())
                .param("customerId", request.customerId())
                .param("total", total)
                .param("paid", paid)
                .param("credit", credit)
                .param("businessDate", businessDate)
                .param("createdAt", now)
                .update();

        String label = "Vente n° " + number;
        for (Line line : lines) {
            if (line.belowFloor()) {
                audit.log("VENTE_SOUS_PLANCHER", label + " : « " + line.productName() + " (" + line.packagingName()
                        + ") » vendu " + line.unitPrice() + " F");
            }
            jdbc.sql("""
                    INSERT INTO sale_item (id, sale_id, product_id, packaging_id, product_name, packaging_name,
                                           factor, quantity, unit_price, list_price, unit_cost_milli, line_total)
                    VALUES (:id, :saleId, :productId, :packagingId, :productName, :packagingName,
                            :factor, :quantity, :unitPrice, :listPrice, :unitCost, :lineTotal)
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("saleId", saleId)
                    .param("productId", line.productId())
                    .param("packagingId", line.packagingId())
                    .param("productName", line.productName())
                    .param("packagingName", line.packagingName())
                    .param("factor", line.factor())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPrice())
                    .param("listPrice", line.listPrice())
                    .param("unitCost", line.unitCostMilli())
                    .param("lineTotal", line.lineTotal())
                    .update();
            stock.removeForSale(line.productId(), Math.multiplyExact(line.quantity(), line.factor()), label);
        }

        String cashSessionId = cash.currentSessionId().orElse(null);
        for (PaymentRequest payment : payments) {
            jdbc.sql("""
                    INSERT INTO payment (id, sale_id, customer_id, method, amount, cash_session_id,
                                         business_date, created_at)
                    VALUES (:id, :saleId, :customerId, :method, :amount, :cashSessionId,
                            :businessDate, :createdAt)
                    """)
                    .param("cashSessionId", cashSessionId)
                    .param("id", UUID.randomUUID().toString())
                    .param("saleId", saleId)
                    .param("customerId", request.customerId())
                    .param("method", payment.method().name())
                    .param("amount", payment.amount())
                    .param("businessDate", businessDate)
                    .param("createdAt", now)
                    .update();
        }

        if (credit > 0) {
            customers.addLedgerEntry(request.customerId(), "CREDIT", credit, saleId, null, label);
        }
        return get(saleId);
    }

    /** Annule une vente par des écritures inverses : rien n'est supprimé. */
    @Transactional
    public SaleView cancel(String saleId, String reason) {
        SaleView sale = get(saleId);
        if ("ANNULEE".equals(sale.status())) {
            throw new BusinessException("Cette vente est déjà annulée.");
        }
        jdbc.sql("UPDATE sale SET status = 'ANNULEE', cancelled_at = ?, cancel_reason = ? WHERE id = ?")
                .params(Timestamps.now(), reason.trim(), saleId)
                .update();

        String label = "Annulation de la vente n° " + sale.number();
        audit.log("VENTE_ANNULEE", "Vente n° " + sale.number() + " de " + sale.total() + " F : " + reason.trim());
        for (SaleItemView item : sale.items()) {
            stock.restoreForCancellation(item.productId(),
                    Math.multiplyExact(item.quantity(), item.factor()), label);
        }
        if (sale.credit() > 0) {
            customers.addLedgerEntry(sale.customerId(), "ANNULATION", -sale.credit(), saleId, null, label);
        }
        return get(saleId);
    }

    @Transactional
    public SaleView get(String saleId) {
        List<SaleItemView> items = jdbc.sql("""
                SELECT product_id, product_name, packaging_name, factor, quantity, unit_price, list_price, line_total
                FROM sale_item
                WHERE sale_id = ?
                ORDER BY rowid
                """)
                .param(saleId)
                .query((rs, i) -> new SaleItemView(rs.getString("product_id"), rs.getString("product_name"),
                        rs.getString("packaging_name"), rs.getLong("factor"), rs.getLong("quantity"),
                        rs.getLong("unit_price"), nullableLong(rs, "list_price"), rs.getLong("line_total")))
                .list();

        List<PaymentView> payments = jdbc.sql("SELECT method, amount FROM payment WHERE sale_id = ? ORDER BY rowid")
                .param(saleId)
                .query((rs, i) -> new PaymentView(PaymentMethod.valueOf(rs.getString("method")),
                        rs.getLong("amount")))
                .list();

        return jdbc.sql("""
                SELECT id, number, customer_id, total, paid, credit, status, business_date, created_at, cancel_reason
                FROM sale
                WHERE id = ?
                """)
                .param(saleId)
                .query((rs, i) -> new SaleView(rs.getString("id"), rs.getLong("number"),
                        rs.getString("customer_id"), rs.getLong("total"), rs.getLong("paid"),
                        rs.getLong("credit"), rs.getString("status"), rs.getString("business_date"),
                        rs.getString("created_at"), rs.getString("cancel_reason"), items, payments))
                .optional()
                .orElseThrow(() -> new NotFoundException("Vente introuvable."));
    }

    @Transactional
    public List<SaleView> listByDate(LocalDate date) {
        List<String> ids = jdbc.sql("SELECT id FROM sale WHERE business_date = ? ORDER BY number DESC")
                .param(date.toString())
                .query(String.class)
                .list();
        return ids.stream().map(this::get).toList();
    }

    @Transactional
    public DailyReport dailyReport(LocalDate date) {
        String day = date.toString();

        long[] sales = jdbc.sql("""
                SELECT COUNT(*), COALESCE(SUM(total), 0), COALESCE(SUM(credit), 0)
                FROM sale
                WHERE business_date = ? AND status = 'VALIDEE'
                """)
                .param(day)
                .query((rs, i) -> new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) })
                .single();

        long costMilli = jdbc.sql("""
                SELECT COALESCE(SUM(si.quantity * si.factor * si.unit_cost_milli), 0)
                FROM sale_item si
                JOIN sale s ON s.id = si.sale_id
                WHERE s.business_date = ? AND s.status = 'VALIDEE'
                """)
                .param(day)
                .query(Long.class)
                .single();
        long costOfGoods = Math.round(costMilli / 1000.0);

        // Les paiements des ventes annulées sont exclus : l'argent a été rendu au client.
        String paymentFilter = """
                FROM payment pay
                LEFT JOIN sale s ON s.id = pay.sale_id
                WHERE pay.business_date = ? AND (pay.sale_id IS NULL OR s.status = 'VALIDEE')
                """;
        long cashReceived = sumPayments(paymentFilter + " AND pay.method = 'ESPECES'", day);
        long mobileMoney = sumPayments(paymentFilter + " AND pay.method = 'MOBILE_MONEY'", day);
        long repayments = sumPayments(paymentFilter + " AND pay.sale_id IS NULL", day);

        long expenses = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM expense WHERE business_date = ?")
                .param(day)
                .query(Long.class)
                .single();
        long grossMargin = sales[1] - costOfGoods;

        return new DailyReport(day, sales[0], sales[1], costOfGoods, grossMargin,
                cashReceived, mobileMoney, sales[2], repayments, expenses, grossMargin - expenses);
    }

    private long sumPayments(String fromAndWhere, String day) {
        return jdbc.sql("SELECT COALESCE(SUM(pay.amount), 0) " + fromAndWhere)
                .param(day)
                .query(Long.class)
                .single();
    }

    private Line resolve(SaleItemRequest item) {
        Catalog catalog = jdbc.sql("""
                SELECT p.id AS product_id, p.name AS product_name, pk.name AS packaging_name, pk.factor,
                       p.avg_cost_milli,
                       (SELECT MIN(pr.floor_price) FROM product_price pr WHERE pr.packaging_id = pk.id) AS min_floor,
                       (SELECT MAX(pr.price) FROM product_price pr WHERE pr.packaging_id = pk.id) AS list_price
                FROM product_packaging pk
                JOIN product p ON p.id = pk.product_id
                WHERE pk.id = ? AND p.active = 1
                """)
                .param(item.packagingId())
                .query((rs, i) -> new Catalog(rs.getString("product_id"), rs.getString("product_name"),
                        rs.getString("packaging_name"), rs.getLong("factor"), rs.getLong("avg_cost_milli"),
                        nullableLong(rs, "min_floor"), nullableLong(rs, "list_price")))
                .optional()
                .orElseThrow(() -> new BusinessException("Un article de la vente n'existe plus au catalogue."));

        // Le vendeur négocie librement jusqu'au prix plancher le plus bas du conditionnement.
        // En dessous, seul le propriétaire connecté peut vendre ; l'opération est inscrite au journal.
        boolean belowFloor = catalog.minFloorPrice() != null && item.unitPrice() < catalog.minFloorPrice();
        if (belowFloor && !CurrentUser.isOwner()) {
            throw new BusinessException("Le prix de " + item.unitPrice() + " F pour « " + catalog.productName()
                    + " (" + catalog.packagingName() + ") » est sous le prix plancher de "
                    + catalog.minFloorPrice() + " F.");
        }

        return new Line(item.packagingId(), catalog.productId(), catalog.productName(), catalog.packagingName(),
                catalog.factor(), item.quantity(), item.unitPrice(), catalog.listPrice(), catalog.avgCostMilli(),
                Math.multiplyExact(item.quantity(), item.unitPrice()), belowFloor);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
