package io.opencanard.console;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the structure of the database a connection points to, for the left panel of the web console:
 * <pre>
 * { "databases": [ { "name": "metadata",
 *       "schemas": [ { "name": "main", "system": false,
 *           "tables":  [ { "name": "t", "type": "TABLE" | "VIEW" } ],
 *           "indexes": [ { "name": "i", "table": "t", "unique": false } ] } ] } ] }
 * </pre>
 * Columns are read on demand with {@link #columns}, so a large catalog opens quickly.
 */
public final class CatalogReader {

	private static final String HIDE_INTERNAL = " NOT IN ('system', 'temp')";

	private CatalogReader() {
	}

	public static Map<String, Object> catalog(Connection conn) throws SQLException {
		// database -> schema -> node, in alphabetical order
		Map<String, Map<String, Map<String, Object>>> tree = new TreeMap<>();

		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT catalog_name, schema_name FROM information_schema.schemata"
						+ " WHERE catalog_name" + HIDE_INTERNAL + " ORDER BY 1, 2")) {
			while (rs.next()) {
				schemaNode(tree, rs.getString(1), rs.getString(2));
			}
		}

		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT table_catalog, table_schema, table_name, table_type"
						+ " FROM information_schema.tables WHERE table_catalog" + HIDE_INTERNAL + " ORDER BY 1, 2, 3")) {
			while (rs.next()) {
				Map<String, Object> table = new LinkedHashMap<>();
				table.put("name", rs.getString(3));
				table.put("type", "VIEW".equalsIgnoreCase(rs.getString(4)) ? "VIEW" : "TABLE");
				list(schemaNode(tree, rs.getString(1), rs.getString(2)), "tables").add(table);
			}
		}

		// duckdb_indexes() does not exist in every build; the tree is useful without it
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT database_name, schema_name, table_name, index_name, is_unique"
						+ " FROM duckdb_indexes() ORDER BY 1, 2, 3, 4")) {
			while (rs.next()) {
				Map<String, Object> index = new LinkedHashMap<>();
				index.put("name", rs.getString(4));
				index.put("table", rs.getString(3));
				index.put("unique", rs.getBoolean(5));
				list(schemaNode(tree, rs.getString(1), rs.getString(2)), "indexes").add(index);
			}
		} catch (SQLException ignored) {
			// no index information
		}

		List<Map<String, Object>> databases = new ArrayList<>();
		for (Map.Entry<String, Map<String, Map<String, Object>>> db : tree.entrySet()) {
			Map<String, Object> dbNode = new LinkedHashMap<>();
			dbNode.put("name", db.getKey());
			dbNode.put("schemas", new ArrayList<>(db.getValue().values()));
			databases.add(dbNode);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("databases", databases);
		return out;
	}

	/** Columns of one table or view, in table order, with the primary key columns flagged. */
	public static List<Map<String, Object>> columns(Connection conn, String catalog, String schema, String table)
			throws SQLException {
		java.util.Set<String> primaryKey = new java.util.HashSet<>();
		try (PreparedStatement ps = conn.prepareStatement("SELECT unnest(constraint_column_names) FROM duckdb_constraints()"
				+ " WHERE database_name = ? AND schema_name = ? AND table_name = ? AND constraint_type = 'PRIMARY KEY'")) {
			ps.setString(1, catalog);
			ps.setString(2, schema);
			ps.setString(3, table);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					primaryKey.add(rs.getString(1));
				}
			}
		} catch (SQLException ignored) {
			// views and system tables have no constraints
		}

		List<Map<String, Object>> columns = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement("SELECT column_name, data_type, is_nullable, column_default"
				+ " FROM information_schema.columns WHERE table_catalog = ? AND table_schema = ? AND table_name = ?"
				+ " ORDER BY ordinal_position")) {
			ps.setString(1, catalog);
			ps.setString(2, schema);
			ps.setString(3, table);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Map<String, Object> column = new LinkedHashMap<>();
					column.put("name", rs.getString(1));
					column.put("type", rs.getString(2));
					column.put("nullable", !"NO".equalsIgnoreCase(rs.getString(3)));
					column.put("default", rs.getString(4));
					column.put("primaryKey", primaryKey.contains(rs.getString(1)));
					columns.add(column);
				}
			}
		}
		return columns;
	}

	private static Map<String, Object> schemaNode(Map<String, Map<String, Map<String, Object>>> tree, String database,
			String schema) {
		return tree.computeIfAbsent(database, k -> new TreeMap<>()).computeIfAbsent(schema, k -> {
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("name", schema);
			node.put("system", "information_schema".equals(schema) || "pg_catalog".equals(schema));
			node.put("tables", new ArrayList<Map<String, Object>>());
			node.put("indexes", new ArrayList<Map<String, Object>>());
			return node;
		});
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Map<String, Object> node, String key) {
		return (List<Map<String, Object>>) node.get(key);
	}
}
