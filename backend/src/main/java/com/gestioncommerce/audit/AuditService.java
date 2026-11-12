package com.gestioncommerce.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.gestioncommerce.common.CurrentUser;
import com.gestioncommerce.common.Timestamps;

/** Journal des opérations sensibles : qui a fait quoi, et quand. On y ajoute, on n'y retire rien. */
@Service
public class AuditService {

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditEntry(String username, String action, String detail, String createdAt) {
    }

    /** Inscrit une opération au nom de la personne connectée. */
    public void log(String action, String detail) {
        log(CurrentUser.username(), action, detail);
    }

    public void log(String username, String action, String detail) {
        jdbc.sql("""
                INSERT INTO audit_log (id, username, action, detail, created_at)
                VALUES (:id, :username, :action, :detail, :createdAt)
                """)
                .param("id", UUID.randomUUID().toString())
                .param("username", username)
                .param("action", action)
                .param("detail", detail)
                .param("createdAt", Timestamps.now())
                .update();
    }

    /** Les 200 dernières opérations, la plus récente en premier. */
    public List<AuditEntry> recent() {
        return jdbc.sql("""
                SELECT username, action, detail, created_at
                FROM audit_log
                ORDER BY created_at DESC, rowid DESC
                LIMIT 200
                """)
                .query((rs, i) -> new AuditEntry(rs.getString("username"), rs.getString("action"),
                        rs.getString("detail"), rs.getString("created_at")))
                .list();
    }
}
