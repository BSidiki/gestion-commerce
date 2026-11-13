package com.gestioncommerce.quotes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.CurrentUser;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.common.Timestamps;
import com.gestioncommerce.sales.SalesDtos.PricedItem;
import com.gestioncommerce.sales.SalesDtos.Pricing;
import com.gestioncommerce.sales.SalesDtos.SaleItemRequest;
import com.gestioncommerce.sales.SalesService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Devis : une proposition de prix remise au client. Il ne touche ni le stock ni la caisse.
 * Les prix suivent les mêmes règles de plancher qu'une vente ; ils sont revérifiés quand
 * le devis devient une vente, car le catalogue a pu changer entre-temps.
 */
@Service
public class QuoteService {

    private static final int DEFAULT_VALIDITY_DAYS = 15;
    private static final int MAX_VALIDITY_DAYS = 365;

    private final JdbcClient jdbc;
    private final SalesService sales;

    public QuoteService(JdbcClient jdbc, SalesService sales) {
        this.jdbc = jdbc;
        this.sales = sales;
    }

    /**
     * @param customerId   client enregistré, facultatif
     * @param customerName nom à afficher si le client n'est pas enregistré
     * @param discount     remise sur le total, en francs CFA
     * @param validDays    durée de validité en jours ; 15 par défaut
     */
    public record QuoteRequest(
            String customerId,
            String customerName,
            @NotEmpty @Valid List<SaleItemRequest> items,
            @PositiveOrZero Long discount,
            Integer validDays,
            String note) {
    }

    /**
     * @param total      total net de la remise
     * @param status     OUVERT, TRANSFORME ou ANNULE
     * @param saleNumber numéro de la vente issue du devis, s'il a été transformé
     */
    public record QuoteView(
            String id,
            long number,
            String customerId,
            String customerName,
            long discount,
            long total,
            String status,
            String validUntil,
            String note,
            String saleId,
            Long saleNumber,
            String createdAt,
            String createdBy,
            List<PricedItem> items) {
    }

    @Transactional
    public QuoteView create(QuoteRequest request) {
        int validDays = request.validDays() == null ? DEFAULT_VALIDITY_DAYS : request.validDays();
        if (validDays < 1 || validDays > MAX_VALIDITY_DAYS) {
            throw new BusinessException("La validité d'un devis va de 1 à " + MAX_VALIDITY_DAYS + " jours.");
        }
        String customerName = blankToNull(request.customerName());
        if (request.customerId() != null && !request.customerId().isBlank()) {
            customerName = jdbc.sql("SELECT name FROM customer WHERE id = ?")
                    .param(request.customerId())
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new NotFoundException("Client introuvable."));
        }
        Pricing pricing = sales.price(request.items(), request.discount());

        String id = UUID.randomUUID().toString();
        long number = jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM quote").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO quote (id, number, customer_id, customer_name, discount, total, valid_until, note,
                                   created_at, created_by)
                VALUES (:id, :number, :customerId, :customerName, :discount, :total, :validUntil, :note,
                        :createdAt, :createdBy)
                """)
                .param("id", id)
                .param("number", number)
                .param("customerId", blankToNull(request.customerId()))
                .param("customerName", customerName)
                .param("discount", pricing.discount())
                .param("total", pricing.total())
                .param("validUntil", LocalDate.now().plusDays(validDays).toString())
                .param("note", blankToNull(request.note()))
                .param("createdAt", Timestamps.now())
                .param("createdBy", CurrentUser.username())
                .update();
        for (PricedItem item : pricing.items()) {
            jdbc.sql("""
                    INSERT INTO quote_item (id, quote_id, product_id, packaging_id, product_name, packaging_name,
                                            factor, quantity, unit_price, line_total)
                    VALUES (:id, :quoteId, :productId, :packagingId, :productName, :packagingName,
                            :factor, :quantity, :unitPrice, :lineTotal)
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("quoteId", id)
                    .param("productId", item.productId())
                    .param("packagingId", item.packagingId())
                    .param("productName", item.productName())
                    .param("packagingName", item.packagingName())
                    .param("factor", item.factor())
                    .param("quantity", item.quantity())
                    .param("unitPrice", item.unitPrice())
                    .param("lineTotal", item.lineTotal())
                    .update();
        }
        return get(id);
    }

    /** Les 100 derniers devis, le plus récent en premier. */
    @Transactional
    public List<QuoteView> list() {
        return jdbc.sql("SELECT id FROM quote ORDER BY number DESC LIMIT 100")
                .query(String.class)
                .list()
                .stream()
                .map(this::get)
                .toList();
    }

    @Transactional
    public QuoteView get(String quoteId) {
        List<PricedItem> items = jdbc.sql("""
                SELECT product_id, packaging_id, product_name, packaging_name, factor, quantity, unit_price,
                       line_total
                FROM quote_item
                WHERE quote_id = ?
                ORDER BY rowid
                """)
                .param(quoteId)
                .query((rs, i) -> new PricedItem(rs.getString("product_id"), rs.getString("packaging_id"),
                        rs.getString("product_name"), rs.getString("packaging_name"), rs.getLong("factor"),
                        rs.getLong("quantity"), rs.getLong("unit_price"), rs.getLong("line_total")))
                .list();
        return jdbc.sql("""
                SELECT q.id, q.number, q.customer_id, q.customer_name, q.discount, q.total, q.status,
                       q.valid_until, q.note, q.sale_id, s.number AS sale_number, q.created_at, q.created_by
                FROM quote q
                LEFT JOIN sale s ON s.id = q.sale_id
                WHERE q.id = ?
                """)
                .param(quoteId)
                .query((rs, i) -> {
                    long saleNumber = rs.getLong("sale_number");
                    Long saleNumberOrNull = rs.wasNull() ? null : saleNumber;
                    return new QuoteView(rs.getString("id"), rs.getLong("number"), rs.getString("customer_id"),
                            rs.getString("customer_name"), rs.getLong("discount"), rs.getLong("total"),
                            rs.getString("status"), rs.getString("valid_until"), rs.getString("note"),
                            rs.getString("sale_id"), saleNumberOrNull, rs.getString("created_at"),
                            rs.getString("created_by"), items);
                })
                .optional()
                .orElseThrow(() -> new NotFoundException("Devis introuvable."));
    }

    @Transactional
    public QuoteView cancel(String quoteId) {
        QuoteView quote = get(quoteId);
        if (!"OUVERT".equals(quote.status())) {
            throw new BusinessException("Ce devis n'est plus ouvert : il a déjà été transformé ou annulé.");
        }
        jdbc.sql("UPDATE quote SET status = 'ANNULE' WHERE id = ?").param(quoteId).update();
        return get(quoteId);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
