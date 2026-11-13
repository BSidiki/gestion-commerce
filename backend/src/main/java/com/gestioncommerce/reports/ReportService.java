package com.gestioncommerce.reports;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.gestioncommerce.common.BusinessException;

/**
 * Rapports de gestion : activité sur une période, état du stock, crédits en attente.
 * Lecture seule ; les ventes annulées sont toujours exclues.
 */
@Service
public class ReportService {

    private static final int MAX_PERIOD_DAYS = 366;
    private static final int TOP_PRODUCTS = 10;
    private static final int MAX_DORMANT = 50;

    private final JdbcClient jdbc;

    public ReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Une journée de la période. Seules les journées où il s'est passé quelque chose sont renvoyées. */
    public record DayLine(String date, long saleCount, long revenue, long costOfGoods, long grossMargin,
            long expenses) {
    }

    /** @param quantity quantité vendue, en unités de base */
    public record ProductLine(String productId, String name, String baseUnit, long quantity, long revenue,
            long grossMargin) {
    }

    public record CategoryLine(String category, long amount) {
    }

    /** @param username nom d'utilisateur, ou null pour les ventes faites avant la création des comptes */
    public record SellerLine(String username, String displayName, long saleCount, long revenue) {
    }

    /**
     * @param revenue            chiffre d'affaires : total des ventes, crédits compris
     * @param estimatedResult    marge brute moins les dépenses
     * @param cash               espèces encaissées (ventes et remboursements de crédits)
     * @param mobileMoney        Mobile Money encaissé (ventes et remboursements de crédits)
     * @param creditGranted      part des ventes laissée à crédit
     * @param repaymentsReceived remboursements de crédits reçus
     */
    public record PeriodReport(
            String from,
            String to,
            long saleCount,
            long revenue,
            long costOfGoods,
            long grossMargin,
            long expenses,
            long estimatedResult,
            long cash,
            long mobileMoney,
            long creditGranted,
            long repaymentsReceived,
            List<DayLine> days,
            List<ProductLine> topProducts,
            List<CategoryLine> expensesByCategory,
            List<SellerLine> sellers) {
    }

    /** @param lastSoldOn date de la dernière vente, ou null si le produit n'a jamais été vendu */
    public record DormantLine(String productId, String name, String baseUnit, long stockQty, long value,
            String lastSoldOn) {
    }

    /**
     * @param stockValue   valeur du stock au coût moyen, en francs CFA
     * @param dormantValue valeur du stock qui ne s'est pas vendu depuis {@code dormantDays} jours
     */
    public record StockReport(
            long productCount,
            long stockValue,
            long lowStockCount,
            int dormantDays,
            long dormantCount,
            long dormantValue,
            List<DormantLine> dormant) {
    }

    /**
     * @param waitingSince date du dernier remboursement, ou du plus ancien crédit s'il n'y en a jamais eu
     * @param daysWaiting  nombre de jours écoulés depuis cette date
     */
    public record CreditLine(String customerId, String name, String phone, long balance, String waitingSince,
            long daysWaiting) {
    }

    public record CreditReport(long customerCount, long totalOutstanding, List<CreditLine> customers) {
    }

    public PeriodReport period(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException("La date de fin précède la date de début.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw new BusinessException("La période ne peut pas dépasser un an.");
        }
        String start = from.toString();
        String end = to.toString();

        // Ventes et coût des marchandises vendues, jour par jour.
        Map<String, long[]> byDay = new TreeMap<>();
        jdbc.sql("""
                SELECT s.business_date, COUNT(*) AS sale_count, COALESCE(SUM(s.total), 0) AS revenue
                FROM sale s
                WHERE s.business_date BETWEEN ? AND ? AND s.status = 'VALIDEE'
                GROUP BY s.business_date
                """)
                .params(start, end)
                .query((rs, i) -> {
                    long[] day = byDay.computeIfAbsent(rs.getString(1), d -> new long[4]);
                    day[0] = rs.getLong("sale_count");
                    day[1] = rs.getLong("revenue");
                    return day;
                })
                .list();
        jdbc.sql("""
                SELECT s.business_date, COALESCE(SUM(si.quantity * si.factor * si.unit_cost_milli), 0) AS cost_milli
                FROM sale_item si
                JOIN sale s ON s.id = si.sale_id
                WHERE s.business_date BETWEEN ? AND ? AND s.status = 'VALIDEE'
                GROUP BY s.business_date
                """)
                .params(start, end)
                .query((rs, i) -> {
                    long[] day = byDay.computeIfAbsent(rs.getString(1), d -> new long[4]);
                    day[2] = Math.round(rs.getLong("cost_milli") / 1000.0);
                    return day;
                })
                .list();
        jdbc.sql("""
                SELECT business_date, COALESCE(SUM(amount), 0) AS amount
                FROM expense
                WHERE business_date BETWEEN ? AND ?
                GROUP BY business_date
                """)
                .params(start, end)
                .query((rs, i) -> {
                    long[] day = byDay.computeIfAbsent(rs.getString(1), d -> new long[4]);
                    day[3] = rs.getLong("amount");
                    return day;
                })
                .list();

        // Les totaux sont la somme des journées : les deux se recoupent au franc près.
        List<DayLine> days = new ArrayList<>();
        long saleCount = 0;
        long revenue = 0;
        long costOfGoods = 0;
        long expenses = 0;
        for (Map.Entry<String, long[]> entry : byDay.entrySet()) {
            long[] d = entry.getValue();
            days.add(new DayLine(entry.getKey(), d[0], d[1], d[2], d[1] - d[2], d[3]));
            saleCount += d[0];
            revenue += d[1];
            costOfGoods += d[2];
            expenses += d[3];
        }
        long grossMargin = revenue - costOfGoods;

        long creditGranted = jdbc.sql("""
                SELECT COALESCE(SUM(credit), 0) FROM sale
                WHERE business_date BETWEEN ? AND ? AND status = 'VALIDEE'
                """)
                .params(start, end).query(Long.class).single();

        // Les paiements des ventes annulées sont exclus : l'argent a été rendu au client.
        String payments = """
                SELECT COALESCE(SUM(pay.amount), 0)
                FROM payment pay
                LEFT JOIN sale s ON s.id = pay.sale_id
                WHERE pay.business_date BETWEEN ? AND ? AND (pay.sale_id IS NULL OR s.status = 'VALIDEE')
                """;
        long cash = sum(payments + " AND pay.method = 'ESPECES'", start, end);
        long mobileMoney = sum(payments + " AND pay.method = 'MOBILE_MONEY'", start, end);
        long repayments = sum(payments + " AND pay.sale_id IS NULL", start, end);

        List<ProductLine> topProducts = jdbc.sql("""
                SELECT si.product_id, p.name, p.base_unit,
                       SUM(si.quantity * si.factor) AS quantity,
                       SUM(si.line_total) AS revenue,
                       SUM(si.quantity * si.factor * si.unit_cost_milli) AS cost_milli
                FROM sale_item si
                JOIN sale s ON s.id = si.sale_id
                JOIN product p ON p.id = si.product_id
                WHERE s.business_date BETWEEN ? AND ? AND s.status = 'VALIDEE'
                GROUP BY si.product_id, p.name, p.base_unit
                ORDER BY revenue DESC, p.name
                LIMIT ?
                """)
                .params(start, end, TOP_PRODUCTS)
                .query((rs, i) -> new ProductLine(rs.getString("product_id"), rs.getString("name"),
                        rs.getString("base_unit"), rs.getLong("quantity"), rs.getLong("revenue"),
                        rs.getLong("revenue") - Math.round(rs.getLong("cost_milli") / 1000.0)))
                .list();

        List<CategoryLine> expensesByCategory = jdbc.sql("""
                SELECT category, SUM(amount) AS amount
                FROM expense
                WHERE business_date BETWEEN ? AND ?
                GROUP BY category
                ORDER BY amount DESC, category
                """)
                .params(start, end)
                .query((rs, i) -> new CategoryLine(rs.getString("category"), rs.getLong("amount")))
                .list();

        List<SellerLine> sellers = jdbc.sql("""
                SELECT s.created_by, u.display_name, COUNT(*) AS sale_count, SUM(s.total) AS revenue
                FROM sale s
                LEFT JOIN app_user u ON u.username = s.created_by
                WHERE s.business_date BETWEEN ? AND ? AND s.status = 'VALIDEE'
                GROUP BY s.created_by, u.display_name
                ORDER BY revenue DESC
                """)
                .params(start, end)
                .query((rs, i) -> new SellerLine(rs.getString("created_by"), rs.getString("display_name"),
                        rs.getLong("sale_count"), rs.getLong("revenue")))
                .list();

        return new PeriodReport(start, end, saleCount, revenue, costOfGoods, grossMargin, expenses,
                grossMargin - expenses, cash, mobileMoney, creditGranted, repayments,
                days, topProducts, expensesByCategory, sellers);
    }

    /** État du stock des produits actifs, et ceux qui ne se vendent plus depuis {@code dormantDays} jours. */
    public StockReport stock(int dormantDays, LocalDate today) {
        if (dormantDays < 1 || dormantDays > 3650) {
            throw new BusinessException("Le nombre de jours doit être compris entre 1 et 3650.");
        }
        String limit = today.minusDays(dormantDays).toString();

        long productCount = jdbc.sql("SELECT COUNT(*) FROM product WHERE active = 1").query(Long.class).single();
        long lowStockCount = jdbc.sql("SELECT COUNT(*) FROM product WHERE active = 1 AND stock_qty <= alert_threshold")
                .query(Long.class).single();
        long stockValueMilli = jdbc.sql("""
                SELECT COALESCE(SUM(stock_qty * avg_cost_milli), 0) FROM product
                WHERE active = 1 AND stock_qty > 0
                """)
                .query(Long.class).single();

        List<DormantLine> dormant = jdbc.sql("""
                SELECT p.id, p.name, p.base_unit, p.stock_qty, p.stock_qty * p.avg_cost_milli AS value_milli,
                       (SELECT MAX(s.business_date)
                        FROM sale_item si
                        JOIN sale s ON s.id = si.sale_id
                        WHERE si.product_id = p.id AND s.status = 'VALIDEE') AS last_sold_on
                FROM product p
                WHERE p.active = 1 AND p.stock_qty > 0
                """)
                .query((rs, i) -> new DormantLine(rs.getString("id"), rs.getString("name"),
                        rs.getString("base_unit"), rs.getLong("stock_qty"),
                        Math.round(rs.getLong("value_milli") / 1000.0), rs.getString("last_sold_on")))
                .list()
                .stream()
                .filter(line -> line.lastSoldOn() == null || line.lastSoldOn().compareTo(limit) < 0)
                .sorted((a, b) -> a.value() != b.value()
                        ? Long.compare(b.value(), a.value())
                        : a.name().compareTo(b.name()))
                .toList();
        long dormantValue = dormant.stream().mapToLong(DormantLine::value).sum();

        return new StockReport(productCount, Math.round(stockValueMilli / 1000.0), lowStockCount, dormantDays,
                dormant.size(), dormantValue, dormant.stream().limit(MAX_DORMANT).toList());
    }

    /** Les clients qui doivent de l'argent, ceux qui attendent depuis le plus longtemps en premier. */
    public CreditReport credits(LocalDate today) {
        List<CreditLine> customers = jdbc.sql("""
                SELECT c.id, c.name, c.phone,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM customer_ledger l
                        WHERE l.customer_id = c.id) AS balance,
                       (SELECT MAX(substr(l.created_at, 1, 10)) FROM customer_ledger l
                        WHERE l.customer_id = c.id AND l.type = 'REMBOURSEMENT') AS last_repayment,
                       (SELECT MIN(substr(l.created_at, 1, 10)) FROM customer_ledger l
                        WHERE l.customer_id = c.id AND l.type = 'CREDIT') AS first_credit
                FROM customer c
                """)
                .query((rs, i) -> {
                    String since = rs.getString("last_repayment") != null
                            ? rs.getString("last_repayment")
                            : rs.getString("first_credit");
                    long days = since == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(since), today));
                    return new CreditLine(rs.getString("id"), rs.getString("name"), rs.getString("phone"),
                            rs.getLong("balance"), since, days);
                })
                .list()
                .stream()
                .filter(line -> line.balance() > 0)
                .sorted((a, b) -> a.daysWaiting() != b.daysWaiting()
                        ? Long.compare(b.daysWaiting(), a.daysWaiting())
                        : Long.compare(b.balance(), a.balance()))
                .toList();
        return new CreditReport(customers.size(), customers.stream().mapToLong(CreditLine::balance).sum(), customers);
    }

    private long sum(String sql, String start, String end) {
        return jdbc.sql(sql).params(start, end).query(Long.class).single();
    }
}
