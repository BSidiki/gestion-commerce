package com.gestioncommerce.customers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.customers.CustomerDtos.CustomerDetail;
import com.gestioncommerce.customers.CustomerDtos.CustomerRequest;
import com.gestioncommerce.customers.CustomerDtos.CustomerView;
import com.gestioncommerce.customers.CustomerDtos.LedgerEntryView;
import com.gestioncommerce.customers.CustomerDtos.RepaymentRequest;

@Service
public class CustomerService {

    private static final String CUSTOMER_WITH_BALANCE = """
            SELECT c.id, c.name, c.phone,
                   (SELECT COALESCE(SUM(l.amount), 0) FROM customer_ledger l WHERE l.customer_id = c.id) AS balance
            FROM customer c
            """;

    private final JdbcClient jdbc;

    public CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CustomerView create(CustomerRequest request) {
        String id = UUID.randomUUID().toString();
        String phone = request.phone() == null || request.phone().isBlank() ? null : request.phone().trim();
        jdbc.sql("INSERT INTO customer (id, name, phone, created_at) VALUES (:id, :name, :phone, :createdAt)")
                .param("id", id)
                .param("name", request.name().trim())
                .param("phone", phone)
                .param("createdAt", Instant.now().toString())
                .update();
        return new CustomerView(id, request.name().trim(), phone, 0);
    }

    @Transactional
    public List<CustomerView> list(boolean withDebtOnly) {
        List<CustomerView> customers = jdbc.sql(CUSTOMER_WITH_BALANCE + " ORDER BY c.name")
                .query((rs, i) -> new CustomerView(rs.getString("id"), rs.getString("name"),
                        rs.getString("phone"), rs.getLong("balance")))
                .list();
        return withDebtOnly ? customers.stream().filter(c -> c.balance() > 0).toList() : customers;
    }

    @Transactional
    public CustomerDetail get(String customerId) {
        CustomerView customer = jdbc.sql(CUSTOMER_WITH_BALANCE + " WHERE c.id = ?")
                .param(customerId)
                .query((rs, i) -> new CustomerView(rs.getString("id"), rs.getString("name"),
                        rs.getString("phone"), rs.getLong("balance")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Client introuvable."));

        List<LedgerEntryView> ledger = jdbc.sql("""
                SELECT type, amount, sale_id, note, created_at
                FROM customer_ledger
                WHERE customer_id = ?
                ORDER BY created_at DESC, rowid DESC
                """)
                .param(customerId)
                .query((rs, i) -> new LedgerEntryView(rs.getString("type"), rs.getLong("amount"),
                        rs.getString("sale_id"), rs.getString("note"), rs.getString("created_at")))
                .list();
        return new CustomerDetail(customer, ledger);
    }

    /** Le client rembourse tout ou partie de ce qu'il doit. */
    @Transactional
    public CustomerDetail repay(String customerId, RepaymentRequest request) {
        long balance = get(customerId).customer().balance();
        if (request.amount() > balance) {
            throw new BusinessException("Le remboursement (" + request.amount()
                    + " F) dépasse ce que le client doit (" + Math.max(balance, 0) + " F).");
        }
        String paymentId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO payment (id, customer_id, method, amount, note, business_date, created_at)
                VALUES (:id, :customerId, :method, :amount, :note, :businessDate, :createdAt)
                """)
                .param("id", paymentId)
                .param("customerId", customerId)
                .param("method", request.method().name())
                .param("amount", request.amount())
                .param("note", request.note())
                .param("businessDate", LocalDate.now().toString())
                .param("createdAt", Instant.now().toString())
                .update();
        addLedgerEntry(customerId, "REMBOURSEMENT", -request.amount(), null, paymentId, request.note());
        return get(customerId);
    }

    // ---------- Utilisé par les ventes ----------

    public void requireExists(String customerId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM customer WHERE id = ?")
                .param(customerId).query(Long.class).single() > 0;
        if (!exists) {
            throw new BusinessException("Client inconnu.");
        }
    }

    public void addLedgerEntry(String customerId, String type, long amount, String saleId, String paymentId,
            String note) {
        jdbc.sql("""
                INSERT INTO customer_ledger (id, customer_id, type, amount, sale_id, payment_id, note, created_at)
                VALUES (:id, :customerId, :type, :amount, :saleId, :paymentId, :note, :createdAt)
                """)
                .param("id", UUID.randomUUID().toString())
                .param("customerId", customerId)
                .param("type", type)
                .param("amount", amount)
                .param("saleId", saleId)
                .param("paymentId", paymentId)
                .param("note", note)
                .param("createdAt", Instant.now().toString())
                .update();
    }
}
