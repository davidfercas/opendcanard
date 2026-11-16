import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Checks how the OpenCanard JDBC driver answers the calls it does not implement: empty metadata results with the
 * JDBC columns (never null), and SQLFeatureNotSupportedException (never a silent null or 0) for the rest. Run by
 * run.sh with the Java source launcher and the driver's -all jar on the class path:
 *
 * <pre>java -cp opencanard-jdbc-driver-*-all.jar OpenCanardJdbcCheck.java [url] [user] [password]</pre>
 *
 * Exits with 1 at the first failed check.
 */
public class OpenCanardJdbcCheck {

	private static int checks = 0;

	interface Call {
		void run() throws Exception;
	}

	public static void main(String[] args) throws Exception {
		String url = args.length > 0 ? args[0] : "jdbc:opencanard://localhost:8815";
		Properties props = new Properties();
		props.setProperty("user", args.length > 1 ? args[1] : "alice");
		props.setProperty("password", args.length > 2 ? args[2] : "alice-pw");
		Class.forName("io.opencanard.driver.jdbc.OpenCanardDriver");

		try (Connection conn = DriverManager.getConnection(url, props)) {
			DatabaseMetaData md = conn.getMetaData();
			check("unwrap", md.unwrap(DatabaseMetaData.class) == md && md.isWrapperFor(DatabaseMetaData.class));
			check("driver version 0.1", md.getDriverMajorVersion() == 0 && md.getDriverMinorVersion() == 1);
			check("forward-only, read-only result sets",
					md.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY)
							&& !md.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE)
							&& md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
							&& !md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));

			empty("getProcedureColumns", md.getProcedureColumns(null, null, "%", "%"), 20);
			empty("getFunctionColumns", md.getFunctionColumns(null, null, "%", "%"), 17);
			empty("getBestRowIdentifier", md.getBestRowIdentifier(null, null, "t", DatabaseMetaData.bestRowSession, true), 8);
			empty("getVersionColumns", md.getVersionColumns(null, null, "t"), 8);
			empty("getUDTs", md.getUDTs(null, null, "%", null), 7);
			empty("getSuperTypes", md.getSuperTypes(null, null, "%"), 6);
			empty("getSuperTables", md.getSuperTables(null, null, "%"), 4);
			empty("getAttributes", md.getAttributes(null, null, "%", "%"), 21);
			empty("getClientInfoProperties", md.getClientInfoProperties(), 4);
			empty("getPseudoColumns", md.getPseudoColumns(null, null, "%", "%"), 12);

			try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT 1 AS one")) {
				check("fetch direction", rs.getFetchDirection() == ResultSet.FETCH_FORWARD);
				check("holdability", rs.getHoldability() == ResultSet.CLOSE_CURSORS_AT_COMMIT);
				check("next", rs.next() && rs.getInt(1) == 1);
				unsupported("getBlob", () -> rs.getBlob(1));
				unsupported("getArray", () -> rs.getArray(1));
				unsupported("updateInt", () -> rs.updateInt(1, 2));
				unsupported("absolute", () -> rs.absolute(1));
				unsupported("previous", () -> rs.previous());
				unsupported("setFetchDirection(REVERSE)", () -> rs.setFetchDirection(ResultSet.FETCH_REVERSE));
				rs.setFetchDirection(ResultSet.FETCH_FORWARD);
				check("rowUpdated", !rs.rowUpdated());
			}
			unsupported("setHoldability(HOLD)", () -> conn.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
		}
		System.out.println("OpenCanard JDBC driver: " + checks + " checks passed");
	}

	private static void empty(String what, ResultSet rs, int columns) throws Exception {
		try (ResultSet r = rs) {
			check(what + " returns a result set", r != null);
			check(what + " has " + columns + " columns", r.getMetaData().getColumnCount() == columns);
			check(what + " is empty", !r.next());
		}
	}

	private static void unsupported(String what, Call call) throws Exception {
		try {
			call.run();
		} catch (SQLFeatureNotSupportedException expected) {
			checks++;
			return;
		}
		fail(what + " should throw SQLFeatureNotSupportedException");
	}

	private static void check(String what, boolean ok) {
		if (!ok) {
			fail(what);
		}
		checks++;
	}

	private static void fail(String what) {
		System.err.println("FAILED: " + what);
		System.exit(1);
	}
}
