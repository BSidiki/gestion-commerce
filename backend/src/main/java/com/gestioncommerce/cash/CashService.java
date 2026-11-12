package com.gestioncommerce.cash;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.cash.CashDtos.CashSessionView;
import com.gestioncommerce.cash.CashDtos.CashStatus;
import com.gestioncommerce.cash.CashDtos.CloseRequest;
import com.gestioncommerce.cash.CashDtos.ExpenseRequest;
import com.gestioncommerce.cash.CashDtos.ExpenseView;
import com.gestioncommerce.cash.CashDtos.OpenRequest;
import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.common.NotFoundException;
import com.gestioncommerce.common.PaymentMethod;
import com.gestioncommerce.common.Timestamps;

/**
 * Caisse : ouverture avec un fond, suivi des espèces attendues, clôture avec
 * le montant compté et l'écart. Les dépenses sont rattachées à la caisse ouverte.
 */
@Service
public class CashService {

    private final JdbcClient jdbc;

    public CashService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Identifiant de la caisse ouverte, s'il y en a une. Sert à rattacher encaissements et dépenses. */
    public Optional<String> currentSessionId() {
        return jdbc.sql("SELECT id FROM cash_session WHERE status = 'OUVERTE'")
                .query(String.class)
                .optional();
    }

    @Transactional
    public CashStatus status() {
        return currentSessionId()
                .map(id -> new CashStatus(true, view(id)))
                .orElseGet(() -> new CashStatus(false, null));
    }

    @Transactional
    public CashSessionView open(OpenRequest request) {
        if (currentSessionId().isPresent()) {
            throw new BusinessException("Une caisse est déjà ouverte. Clôturez-la avant d'en ouvrir une autre.");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO cash_session (id, status, opening_float, opened_at) VALUES (?, 'OUVERTE', ?, ?)")
                .params(id, request.openingFloat(), Timestamps.now())
                .update();
        return view(id);
    }

    @Transactional
    public CashSessionView close(CloseRequest request) {
        String id = currentSessionId()
                .orElseThrow(() -> new BusinessException("Aucune caisse n'est ouverte."));
        long expected = view(id).expectedCash();
        jdbc.sql("""
                UPDATE cash_session
                SET status = 'CLOTUREE', closed_at = :closedAt, expected_cash = :expected,
                    counted_cash = :counted, difference = :difference, note = :note
                WHERE id = :id
                """)
                .param("closedAt", Timestamps.now())
                .param("expected", expected)
                .param("counted", request.countedCash())
                .param("difference", request.countedCash() - expected)
                .param("note", request.note() == null || request.note().isBlank() ? null : request.note().trim())
                .param("id", id)
                .update();
        return view(id);
    }

    /** Les 30 dernières sessions, la plus récente en premier. */
    @Transactional
    public List<CashSessionView> history() {
        List<String> ids = jdbc.sql("SELECT id FROM cash_session ORDER BY opened_at DESC, rowid DESC LIMIT 30")
                .query(String.class)
                .list();
        return ids.stream().map(this::view).toList();
    }

    // ---------- Dépenses ----------

    @Transactional
    public ExpenseView addExpense(ExpenseRequest request) {
        String id = UUID.randomUUID().toString();
        String now = Timestamps.now();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        jdbc.sql("""
                INSERT INTO expense (id, category, amount, method, note, cash_session_id, business_date, created_at)
                VALUES (:id, :category, :amount, :method, :note, :cashSessionId, :businessDate, :createdAt)
                """)
                .param("id", id)
                .param("category", request.category().trim())
                .param("amount", request.amount())
                .param("method", request.method().name())
                .param("note", note)
                .param("cashSessionId", currentSessionId().orElse(null))
                .param("businessDate", LocalDate.now().toString())
                .param("createdAt", now)
                .update();
        return new ExpenseView(id, request.category().trim(), request.amount(), request.method(), note, now);
    }

    @Transactional
    public List<ExpenseView> expensesOf(LocalDate date) {
        return jdbc.sql("""
                SELECT id, category, amount, method, note, created_at
                FROM expense
                WHERE business_date = ?
                ORDER BY created_at DESC, rowid DESC
                """)
                .param(date.toString())
                .query((rs, i) -> new ExpenseView(rs.getString("id"), rs.getString("category"),
                        rs.getLong("amount"), PaymentMethod.valueOf(rs.getString("method")),
                        rs.getString("note"), rs.getString("created_at")))
                .list();
    }

    // ---------- Lecture d'une session ----------

    private CashSessionView view(String sessionId) {
        // Les paiements des ventes annulées sont exclus : l'argent a été rendu au client.
        long cashIn = jdbc.sql("""
                SELECT COALESCE(SUM(pay.amount), 0)
                FROM payment pay
                LEFT JOIN sale s ON s.id = pay.sale_id
                WHERE pay.cash_session_id = ? AND pay.method = 'ESPECES'
                  AND (pay.sale_id IS NULL OR s.status = 'VALIDEE')
                """)
                .param(sessionId)
                .query(Long.class)
                .single();

        long cashExpenses = jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0)
                FROM expense
                WHERE cash_session_id = ? AND method = 'ESPECES'
                """)
                .param(sessionId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                SELECT id, status, opening_float, opened_at, closed_at, expected_cash, counted_cash, difference, note
                FROM cash_session
                WHERE id = ?
                """)
                .param(sessionId)
                .query((rs, i) -> {
                    long openingFloat = rs.getLong("opening_float");
                    Long storedExpected = nullableLong(rs, "expected_cash");
                    long expected = storedExpected != null ? storedExpected : openingFloat + cashIn - cashExpenses;
                    return new CashSessionView(rs.getString("id"), rs.getString("status"), openingFloat,
                            rs.getString("opened_at"), rs.getString("closed_at"), cashIn, cashExpenses, expected,
                            nullableLong(rs, "counted_cash"), nullableLong(rs, "difference"), rs.getString("note"));
                })
                .optional()
                .orElseThrow(() -> new NotFoundException("Session de caisse introuvable."));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
