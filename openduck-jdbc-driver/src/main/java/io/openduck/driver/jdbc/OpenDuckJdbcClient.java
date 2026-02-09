package io.openduck.driver.jdbc;

import java.sql.*;

public class OpenDuckJdbcClient {

    public static void main(String[] args) throws Exception {

        // 1️⃣ Load driver (optional if using SPI, but explicit is safer now)
        Class.forName("io.openduck.driver.jdbc.OpenDuckDriver");

        // 2️⃣ JDBC URL (match your driver format)
        String url = "jdbc:openduck://localhost:8815";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * from cities where id <100 order by id")) {

            System.out.println("Connected to OpenDuck!\n");

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
    }
}
