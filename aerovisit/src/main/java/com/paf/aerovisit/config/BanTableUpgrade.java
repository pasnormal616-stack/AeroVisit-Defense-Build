package com.paf.aerovisit.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// 🪪 One-time database upgrade for identity bans (IdentityBans): blacklisted_faces.face_encoding was created NOT NULL,
// and Hibernate's ddl-auto=update adds new columns but never relaxes an existing one, so a ban without face data
// (AI features off) would fail to save. On start-up, if the column is still NOT NULL on MySQL, it is made nullable.
// Safe to run on every start: it does nothing once the column allows NULL, and a failure is only logged.
@Component
public class BanTableUpgrade implements ApplicationRunner {

    private final DataSource dataSource;

    public BanTableUpgrade(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            if (!meta.getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("mysql")) return;
            boolean notNull = false;
            try (ResultSet cols = meta.getColumns(c.getCatalog(), null, "blacklisted_faces", "face_encoding")) {
                if (cols.next()) notNull = cols.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
            }
            if (!notNull) return;
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE blacklisted_faces MODIFY face_encoding TEXT NULL");
            }
            System.out.println("🪪 Ban list upgraded: bans without face data can now be saved (face_encoding is nullable).");
        } catch (Exception e) {
            System.err.println("⚠️ Could not upgrade the ban list table (bans without face data may fail to save): " + e.getMessage());
        }
    }
}
