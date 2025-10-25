package com.maksym.habits.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class DataController {

    private final DataSource dataSource;

    public DataController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // -------------------------------------------------------------------------
    // EXPORT: GET /api/export-db
    // Snapshot the LIVE SQLite DB into a temp file (VACUUM INTO) and stream it.
    // -------------------------------------------------------------------------
    @GetMapping("/export-db")
    public ResponseEntity<InputStreamResource> exportLiveSqlite() throws Exception {
        Path temp = Files.createTempFile("loop_export_", ".db");
        Files.deleteIfExists(temp); // VACUUM INTO requires that the target does not exist

        try (Connection conn = dataSource.getConnection()) {
            run(conn, "PRAGMA busy_timeout=10000");
            boolean wasAuto = conn.getAutoCommit();
            try {
                conn.setAutoCommit(true); // VACUUM INTO must run outside a transaction
                String target = temp.toAbsolutePath().toString().replace("'", "''");
                run(conn, "VACUUM INTO '" + target + "'");
            } finally {
                try { conn.setAutoCommit(wasAuto); } catch (SQLException ignore) {}
            }
        } catch (SQLException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignore) {}
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("into")) {
                throw new SQLException("VACUUM INTO unsupported by current SQLite; use sqlite-jdbc >= 3.27.0.", e);
            }
            throw e;
        }

        InputStream in = Files.newInputStream(temp, StandardOpenOption.DELETE_ON_CLOSE);
        InputStreamResource body = new InputStreamResource(in);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Loop_Export.db\"");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    // -------------------------------------------------------------------------
    // IMPORT: POST /api/import-db  (multipart form, field name: file)
    // Replace the LIVE DB with the uploaded .db
    // -------------------------------------------------------------------------
    @PostMapping(value = "/import-db", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> importDb(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded.");
        }

        Path uploaded = Files.createTempFile("loop_upload_", ".db");
        try {
            // Save upload to a temp file first
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, uploaded, StandardCopyOption.REPLACE_EXISTING);
            }

            try (Connection conn = dataSource.getConnection()) {
                run(conn, "PRAGMA busy_timeout=10000");
                boolean wasAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);

                // Unique alias per request to avoid collisions with pooled connections
                String alias = "src_" + UUID.randomUUID().toString().replace("-", "");

                boolean detachNeeded = false;
                try {
                    run(conn, "PRAGMA foreign_keys=OFF");

                    String srcPath = uploaded.toAbsolutePath().toString().replace("'", "''");
                    run(conn, "ATTACH DATABASE '" + srcPath + "' AS " + alias);
                    detachNeeded = true;

                    // 1) Drop everything from main (views -> triggers -> indexes -> tables)
                    dropAllFromMain(conn);

                    // 2) Create tables from the attached DB (collect DDL first, then execute)
                    for (String ddl : readObjectSqlFromAttached(conn, alias, "table")) {
                        run(conn, ddl + ";");
                    }

                    // 3) Copy data for each user table
                    for (String t : listTables(conn, alias)) {
                        List<String> cols = getColumnsAttached(conn, alias, t);
                        if (cols.isEmpty()) continue;
                        String colList = cols.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(","));
                        String sql = "INSERT INTO \"" + t + "\" (" + colList + ") SELECT " + colList + " FROM " + alias + ".\"" + t + "\"";
                        run(conn, sql);
                    }

                    // 4) Recreate views, indexes, triggers (collect then execute)
                    for (String ddl : readObjectSqlFromAttached(conn, alias, "view"))   run(conn, ddl + ";");
                    for (String ddl : readObjectSqlFromAttached(conn, alias, "index"))  run(conn, ddl + ";");
                    for (String ddl : readObjectSqlFromAttached(conn, alias, "trigger"))run(conn, ddl + ";");

                    // 5) Copy PRAGMA user_version from the uploaded DB
                    int userVersion = getIntPragma(conn, alias, "user_version");
                    run(conn, "PRAGMA user_version=" + userVersion);

                    // Commit the migration FIRST…
                    conn.commit();

                    // …then DETACH in autocommit mode (prevents 'database is locked' on some setups)
                    boolean postCommitAuto = conn.getAutoCommit();
                    try {
                        conn.setAutoCommit(true);
                        if (detachNeeded) {
                            run(conn, "DETACH DATABASE " + alias);
                            detachNeeded = false;
                        }
                    } finally {
                        try { conn.setAutoCommit(postCommitAuto); } catch (SQLException ignore) {}
                    }

                    // restore PRAGMA (after detach; not critical but tidy)
                    run(conn, "PRAGMA foreign_keys=ON");
                } catch (Exception e) {
                    // Best-effort detach before rollback
                    try { if (detachNeeded) run(conn, "DETACH DATABASE " + alias); } catch (Exception ignore) {}
                    conn.rollback();
                    throw e;
                } finally {
                    // Extra safety: if somehow still attached, detach now (ignore errors)
                    try { if (detachNeeded) run(conn, "DETACH DATABASE " + alias); } catch (Exception ignore) {}
                    try { run(conn, "PRAGMA foreign_keys=ON"); } catch (Exception ignore) {}
                    try { conn.setAutoCommit(wasAuto); } catch (SQLException ignore) {}
                }
            }

            return ResponseEntity.ok("Import completed successfully.");
        } finally {
            try { Files.deleteIfExists(uploaded); } catch (IOException ignore) {}
        }
    }

    // -------------------------------------------------------------------------
    // (Optional) keep JSON-based export if you still use it elsewhere.
    // -------------------------------------------------------------------------
    @PostMapping(value = "/export-db", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InputStreamResource> exportDbFromJson(@RequestBody JsonNode dump) throws Exception {
        JsonNode schema = required(dump, "schema");
        JsonNode objects = required(schema, "objects");
        JsonNode data = required(dump, "data");

        Path temp = buildSqliteFromDump(schema, objects, data);
        InputStream in = Files.newInputStream(temp, StandardOpenOption.DELETE_ON_CLOSE);
        InputStreamResource body = new InputStreamResource(in);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Loop_Export.db\"");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    // ============================== Helpers ==============================

    private static JsonNode required(JsonNode node, String name) {
        if (node == null || !node.has(name) || node.get(name).isNull())
            throw new IllegalArgumentException("Missing field: " + name);
        return node.get(name);
    }

    private static void run(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(sql); }
    }

    private static List<String> listTables(Connection conn, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        String q = "SELECT name FROM " + schema + ".sqlite_master " +
                "WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(q)) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }

    private static List<String> getColumnsAttached(Connection conn, String schema, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        String q = "PRAGMA " + schema + ".table_info(\"" + table + "\")";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(q)) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    // Read DDL from attached DB, fully close the cursor, then execute elsewhere.
    private static List<String> readObjectSqlFromAttached(Connection conn, String schema, String type) throws SQLException {
        List<String> ddls = new ArrayList<>();
        String q = "SELECT sql FROM " + schema + ".sqlite_master " +
                "WHERE type=? AND sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ddl = rs.getString(1);
                    if (ddl != null && !ddl.isBlank()) ddls.add(ddl.trim());
                }
            }
        }
        return ddls;
    }

    private static int getIntPragma(Connection conn, String schema, String pragma) throws SQLException {
        String q = "PRAGMA " + schema + "." + pragma;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(q)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void dropAllFromMain(Connection conn) throws SQLException {
        dropByType(conn, "view",    (name, tbl) -> "DROP VIEW IF EXISTS \"" + name + "\"");
        dropByType(conn, "trigger", (name, tbl) -> "DROP TRIGGER IF EXISTS \"" + name + "\"");
        dropIndexes(conn);
        dropByType(conn, "table",   (name, tbl) -> "DROP TABLE IF EXISTS \"" + name + "\"");
    }

    private interface Dropper { String sql(String name, String tblName); }

    private static void dropByType(Connection conn, String type, Dropper dropper) throws SQLException {
        String q = "SELECT name, tbl_name FROM main.sqlite_master WHERE type=? AND name NOT LIKE 'sqlite_%'";
        try (PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> drops = new ArrayList<>();
                while (rs.next()) {
                    String name = rs.getString(1);
                    String tbl = rs.getString(2);
                    drops.add(dropper.sql(name, tbl));
                }
                for (String d : drops) run(conn, d);
            }
        }
    }

    private static void dropIndexes(Connection conn) throws SQLException {
        String q = "SELECT name FROM main.sqlite_master " +
                "WHERE type='index' AND sql IS NOT NULL AND name NOT LIKE 'sqlite_%'";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(q)) {
            List<String> names = new ArrayList<>();
            while (rs.next()) names.add(rs.getString(1));
            for (String n : names) run(conn, "DROP INDEX IF EXISTS \"" + n + "\"");
        }
    }

    // ---- JSON -> .db builder kept for completeness (not used by GET import) ----
    private Path buildSqliteFromDump(JsonNode schema, JsonNode objects, JsonNode data) throws Exception {
        Path temp = Files.createTempFile("loop_export_", ".db");
        String url = "jdbc:sqlite:" + temp.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(url)) {
            conn.setAutoCommit(false);
            run(conn, "PRAGMA foreign_keys=OFF");
            run(conn, "PRAGMA journal_mode=MEMORY");
            run(conn, "PRAGMA synchronous=OFF");

            execObjectsByType(conn, objects, "table");

            Iterator<String> tableNames = data.fieldNames();
            while (tableNames.hasNext()) {
                String table = tableNames.next();
                JsonNode rows = data.get(table);
                if (rows.isArray() && rows.size() > 0) {
                    List<String> cols = getColumns(conn, table);
                    String placeholders = cols.stream().map(c -> "?").collect(Collectors.joining(","));
                    String colList = cols.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(","));
                    String insSql = "INSERT INTO \"" + table + "\" (" + colList + ") VALUES (" + placeholders + ")";
                    try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                        for (JsonNode row : (Iterable<JsonNode>) rows::elements) {
                            for (int i = 0; i < cols.size(); i++) {
                                String c = cols.get(i);
                                JsonNode v = row.get(c);
                                bindFromJson(ps, i + 1, v);
                            }
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
            }

            execObjectsByType(conn, objects, "view");
            execObjectsByType(conn, objects, "index");
            execObjectsByType(conn, objects, "trigger");

            int userVersion = schema.has("user_version") ? schema.get("user_version").asInt(0) : 0;
            run(conn, "PRAGMA user_version=" + userVersion);
            run(conn, "PRAGMA foreign_keys=ON");
            conn.commit();
        }
        return temp;
    }

    private static void execObjectsByType(Connection conn, JsonNode objects, String type) throws SQLException {
        for (JsonNode obj : (Iterable<JsonNode>) objects::elements) {
            if (type.equals(obj.path("type").asText()) && obj.hasNonNull("sql")) {
                String ddl = obj.get("sql").asText().trim();
                if (!ddl.isEmpty()) run(conn, ddl + ";");
            }
        }
    }

    private static List<String> getColumns(Connection conn, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    private static void bindFromJson(PreparedStatement ps, int idx, JsonNode v) throws SQLException {
        if (v == null || v.isNull()) { ps.setNull(idx, Types.NULL); return; }
        if (v.isIntegralNumber()) { ps.setLong(idx, v.asLong()); return; }
        if (v.isFloatingPointNumber()) { ps.setDouble(idx, v.asDouble()); return; }
        if (v.isBoolean()) { ps.setInt(idx, v.asBoolean() ? 1 : 0); return; }
        ps.setString(idx, v.asText());
    }

    @GetMapping(value = "/repetitions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> listRepetitions(
            @RequestParam("from") long fromInclusive,
            @RequestParam("to") long toExclusive) throws Exception {

        final String sql =
                "SELECT habit, timestamp, value, notes " +
                        "FROM Repetitions " +
                        "WHERE timestamp >= ? AND timestamp < ? " +
                        "ORDER BY timestamp ASC, habit ASC";

        long fromMs = normalizeUnitsToMs(fromInclusive);
        long toMs   = normalizeUnitsToMs(toExclusive);

        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        try (java.sql.Connection conn = dataSource.getConnection()) {
            try (java.sql.Statement s = conn.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); }
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, fromMs);
                ps.setLong(2, toMs);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long ts = rs.getLong("timestamp");
                        // ensure ms on the way out
                        if (ts < 100_000_000_000L) ts = ts * 1000L;

                        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("habit", rs.getLong("habit"));
                        row.put("timestamp", ts);
                        long v = rs.getLong("value");
                        row.put("value", rs.wasNull() ? null : v);
                        String notes = rs.getString("notes");
                        row.put("notes", rs.wasNull() ? null : notes);
                        out.add(row);
                    }
                }
            }
        }
        return ResponseEntity.ok(out);
    }

    @PatchMapping(value = "/habits/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String,Object>> patchHabit(
            @PathVariable("id") long id,
            @org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode body) throws Exception {

        String desc = body.hasNonNull("description") ? body.get("description").asText() : null;

        try (java.sql.Connection conn = dataSource.getConnection()) {
            try (java.sql.Statement s = conn.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); }
            if (desc != null) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement("UPDATE Habits SET description=? WHERE id=?")) {
                    ps.setString(1, desc);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
            // Return the updated row (or just echo)
            java.util.Map<String,Object> out = new java.util.HashMap<>();
            out.put("id", id);
            out.put("description", desc);
            return ResponseEntity.ok(out);
        }
    }

    @PutMapping(value = "/habits/reorder", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reorderHabits(@RequestBody com.fasterxml.jackson.databind.JsonNode body) throws Exception {
        // Accept either { "order": [ids...] } or a raw array [ids...]
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (body.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode n : body) {
                if (n != null && n.isNumber()) ids.add(n.asLong());
            }
        } else if (body.has("order") && body.get("order").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode n : body.get("order")) {
                if (n != null && n.isNumber()) ids.add(n.asLong());
            }
        } else {
            return ResponseEntity.badRequest().body("Expected JSON array or {\"order\":[...]}");
        }

        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body("Order list is empty.");
        }

        try (java.sql.Connection conn = dataSource.getConnection()) {
            try (java.sql.Statement s = conn.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); }
            boolean wasAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = conn.prepareStatement("UPDATE Habits SET position=? WHERE id=?")) {
                for (int i = 0; i < ids.size(); i++) {
                    ps.setInt(1, i);
                    ps.setLong(2, ids.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            try { conn.setAutoCommit(wasAuto); } catch (Exception ignore) {}
        }

        return ResponseEntity.ok("Reordered.");
    }

    @PostMapping(value = "/repetitions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> upsertRepetition(@RequestBody com.fasterxml.jackson.databind.JsonNode body) throws Exception {
        if (!body.hasNonNull("habitId") || !body.hasNonNull("timestamp")) {
            return ResponseEntity.badRequest().body("Missing habitId or timestamp.");
        }
        long habitId = body.get("habitId").asLong();
        long tsMs    = normalizeUnitsToMs(body.get("timestamp").asLong());
        long dayUtc  = toUtcMidnight(tsMs);
        com.fasterxml.jackson.databind.JsonNode v = body.get("value");
        Long value = (v == null || v.isNull()) ? null : v.asLong();
        String notes = body.hasNonNull("notes") ? body.get("notes").asText() : null;

        // Requires a unique index on (habit, timestamp). If you don't have it, add:
        // CREATE UNIQUE INDEX IF NOT EXISTS ux_repetitions_habit_day ON Repetitions(habit, timestamp);

        try (java.sql.Connection conn = dataSource.getConnection()) {
            try (java.sql.Statement s = conn.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); }
            boolean was = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (value == null) {
                    try (java.sql.PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM Repetitions WHERE habit=? AND timestamp=?")) {
                        del.setLong(1, habitId);
                        del.setLong(2, dayUtc);
                        del.executeUpdate();
                    }
                } else {
                    try (java.sql.PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO Repetitions(habit, timestamp, value, notes) " +
                                    "VALUES(?,?,?,?) " +
                                    "ON CONFLICT(habit, timestamp) DO UPDATE SET value=excluded.value, notes=excluded.notes")) {
                        ps.setLong(1, habitId);
                        ps.setLong(2, dayUtc);
                        ps.setLong(3, value);
                        if (notes == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, notes);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } finally {
                try { conn.setAutoCommit(was); } catch (Exception ignore) {}
            }
        }
        return ResponseEntity.ok("Saved");
    }

    @DeleteMapping(value = "/repetitions", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> deleteRepetition(
            @RequestParam("habitId") long habitId,
            @RequestParam("timestamp") long timestamp) throws Exception {
        long tsMs   = normalizeUnitsToMs(timestamp);
        long dayUtc = toUtcMidnight(tsMs);
        try (java.sql.Connection conn = dataSource.getConnection()) {
            try (java.sql.Statement s = conn.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); }
            try (java.sql.PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM Repetitions WHERE habit=? AND timestamp=?")) {
                del.setLong(1, habitId);
                del.setLong(2, dayUtc);
                del.executeUpdate();
            }
        }
        return ResponseEntity.ok("Deleted");
    }

    private static long normalizeUnitsToMs(long ts) {
        // if looks like seconds since epoch, convert to ms
        return (ts < 100_000_000_000L) ? ts * 1000L : ts;
    }
    private static long toUtcMidnight(long epochMs) {
        final long MS_PER_DAY = 86_400_000L;
        return epochMs - Math.floorMod(epochMs, MS_PER_DAY);
    }


}
