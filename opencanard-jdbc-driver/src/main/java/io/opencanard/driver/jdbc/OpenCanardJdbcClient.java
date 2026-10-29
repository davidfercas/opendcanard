package io.opencanard.driver.jdbc;

import java.sql.*;
import java.util.Properties;

public class OpenCanardJdbcClient {

    /**
     * Usage: OpenCanardJdbcClient [url] [user] [password] [sql]
     * Add -Dopencanard.jdbc.debug=true (or ?debug=true to the url) for debug output.
     */
    public static void main(String[] args) throws Exception {

        // 1️⃣ Load driver (optional if using SPI, but explicit is safer now)
        Class.forName("io.opencanard.driver.jdbc.OpenCanardDriver");

        // 2️⃣ JDBC URL (match your driver format)
        String url = args.length > 0 ? args[0] : "jdbc:opencanard://localhost:8815";
        String sql = args.length > 3 ? args[3] : "SELECT * from cities order by id";
        Properties props = new Properties();
        props.setProperty("user", args.length > 1 ? args[1] : "admin");
        props.setProperty("password", args.length > 2 ? args[2] : "admin");

        try (Connection conn = DriverManager.getConnection(url ,props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            System.out.println("Connected to OpenCanard!\n");

          

            
            // 3️⃣ Print metadata
            ResultSetMetaData meta = rs.getMetaData();
          
            int cols = meta.getColumnCount();

            for (int i = 1; i <= cols; i++) {
                System.out.print(meta.getColumnName(i) + "\t");
            }
            System.out.println();
            System.out.println("---------------------");

            
            // 4️⃣ Print rows
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    System.out.print(rs.getObject(i) + "\t");
                }
                System.out.println();
            }
        }
        
        System.out.println();
        System.out.println("End");
        
    }
}
