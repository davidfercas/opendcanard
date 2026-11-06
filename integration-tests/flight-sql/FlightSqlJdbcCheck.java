import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Drives a live OpenCanard server with the standard Apache Arrow Flight SQL JDBC driver (flight-sql-jdbc-driver,
 * already in lib/external of the server distribution). Run by run.sh with the Java source launcher:
 *
 * <pre>java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/external/*" FlightSqlJdbcCheck.java [host:port] [user] [password]</pre>
 *
 * Exits with 1 at the first failed check.
 */
public class FlightSqlJdbcCheck {

	private static int checks = 0;

	public static void main(String[] args) throws Exception {
		String hostPort = args.length > 0 ? args[0] : "localhost:8815";
		Properties props = new Properties();
		props.setProperty("user", args.length > 1 ? args[1] : "bob");
		props.setProperty("password", args.length > 2 ? args[2] : "bob-pw");
		String url = "jdbc:arrow-flight-sql://" + hostPort + "/?useEncryption=false";

		try (Connection conn = DriverManager.getConnection(url, props)) {
			try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT 42 AS answer, 'duck' AS word")) {
				check(rs.next(), "a row comes back");
				check(rs.getInt("answer") == 42, "int column");
				check("duck".equals(rs.getString("word")), "string column");
			}

			try (Statement st = conn.createStatement()) {
				st.execute("DROP TABLE IF EXISTS jdbc_items");
				st.execute("CREATE TABLE jdbc_items(id INTEGER PRIMARY KEY, label VARCHAR)");
			}

			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO jdbc_items VALUES (?, ?)")) {
				for (int i = 1; i <= 3; i++) {
					ps.setInt(1, i);
					ps.setString(2, "item " + i);
					check(ps.executeUpdate() == 1, "prepared insert " + i + " changes one row");
				}
			}

			try (Statement st = conn.createStatement()) {
				check(st.executeUpdate("UPDATE jdbc_items SET label = upper(label) WHERE id >= 2") == 2,
						"executeUpdate reports the changed rows");
			}

			try (PreparedStatement ps = conn.prepareStatement("SELECT label FROM jdbc_items WHERE id = ?")) {
				ps.setInt(1, 3);
				try (ResultSet rs = ps.executeQuery()) {
					check(rs.next() && "ITEM 3".equals(rs.getString(1)), "prepared query with a parameter");
				}
				ps.setInt(1, 1);
				try (ResultSet rs = ps.executeQuery()) {
					check(rs.next() && "item 1".equals(rs.getString(1)), "same prepared query, new parameter");
				}
			}

			DatabaseMetaData md = conn.getMetaData();
			check(md.getDatabaseProductName().contains("OpenCanard"), "product name from GetSqlInfo");

			List<String> catalogs = column(md.getCatalogs(), "TABLE_CAT");
			check(!catalogs.isEmpty(), "getCatalogs: " + catalogs);

			List<String> schemas = column(md.getSchemas(), "TABLE_SCHEM");
			check(schemas.contains("main"), "getSchemas has main: " + schemas);

			List<String> tables = column(md.getTables(null, "main", "jdbc%", null), "TABLE_NAME");
			check(tables.contains("jdbc_items"), "getTables: " + tables);

			List<String> columns = column(md.getColumns(null, "main", "jdbc_items", null), "COLUMN_NAME");
			check(columns.equals(List.of("id", "label")), "getColumns: " + columns);

			List<String> keys = column(md.getPrimaryKeys(null, "main", "jdbc_items"), "COLUMN_NAME");
			check(keys.equals(List.of("id")), "getPrimaryKeys: " + keys);
		}

		props.setProperty("password", "wrong");
		boolean refused = false;
		try (Connection conn = DriverManager.getConnection(url, props);
				Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT 1")) {
			rs.next();
		} catch (Exception e) {
			refused = true;
		}
		check(refused, "a wrong password is refused");

		System.out.println("Flight SQL JDBC: all " + checks + " checks passed");
	}

	private static List<String> column(ResultSet rs, String name) throws Exception {
		List<String> out = new ArrayList<>();
		try (rs) {
			while (rs.next()) {
				out.add(rs.getString(name));
			}
		}
		return out;
	}

	private static void check(boolean ok, String what) {
		checks++;
		if (!ok) {
			System.err.println("FAILED: " + what);
			System.exit(1);
		}
		System.out.println("ok - " + what);
	}
}
