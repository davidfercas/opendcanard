import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Runs {@code SELECT 1} against an OpenCanard server through JDBC.
 *
 * <p>Two drivers are supported; pick one with the first argument:
 * <ul>
 *   <li>{@code flightsql} – the Apache Arrow Flight SQL JDBC driver (recommended),
 *       jar {@code org.apache.arrow:flight-sql-jdbc-driver}.</li>
 *   <li>{@code opencanard} – the bundled driver,
 *       jar {@code opencanard-jdbc-driver/target/opencanard-jdbc-driver-0.1-all.jar}.</li>
 * </ul>
 *
 * <p>Run it straight from source (Java 17+), with the driver jar on the class path:
 * <pre>
 * java -cp &lt;driver jar&gt; SelectOne.java opencanard [host] [port] [user] [password]
 * java --add-opens=java.base/java.nio=ALL-UNNAMED -cp &lt;driver jar&gt; SelectOne.java flightsql
 * </pre>
 * The password can also come from the {@code OPENCANARD_PASSWORD} environment variable.
 */
public class SelectOne {

    private static final String SQL = "SELECT 1";

    public static void main(String[] args) throws Exception {
        String driver = arg(args, 0, "opencanard");
        String host = arg(args, 1, "localhost");
        String port = arg(args, 2, "8815");
        String user = arg(args, 3, "admin");
        String password = arg(args, 4, envOr("OPENCANARD_PASSWORD", "change-me"));

        String driverClass;
        String url;
        switch (driver) {
            case "flightsql":
                driverClass = "org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver";
                // The Flight port has no TLS yet.
                url = "jdbc:arrow-flight-sql://" + host + ":" + port + "/?useEncryption=false";
                break;
            case "opencanard":
                driverClass = "io.opencanard.driver.jdbc.OpenCanardDriver";
                url = "jdbc:opencanard://" + host + ":" + port;
                break;
            default:
                System.err.println("Unknown driver '" + driver + "': use 'flightsql' or 'opencanard'");
                System.exit(2);
                return;
        }

        // Load the driver explicitly: the bundled jar has no META-INF/services entry.
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            System.err.println("[" + driver + "] driver class " + driverClass
                    + " not found: put the driver jar on the class path (-cp <jar>)");
            System.exit(2);
        }

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);

        try (Connection conn = DriverManager.getConnection(url, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SQL)) {
            while (rs.next()) {
                System.out.println("[" + driver + "] " + SQL + " -> " + rs.getInt(1));
            }
        } catch (SQLException e) {
            System.err.println("[" + driver + "] failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
