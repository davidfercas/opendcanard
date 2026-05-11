package io.openduck.user;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;

public class UserRepository {

    private static final String DB_URL = "jdbc:duckdb:D:\\GitProjects\\openduck\\openduck-server\\data\\openduck.duckdb";

    
    
    public UserRepository() {
		super();
		// TODO Auto-generated constructor stub
	}



	public User getUser(Connection metadatadb, String username) throws Exception {

        String sql = "SELECT username, password_hash FROM main.openduck_users WHERE username = ?;";

        try (PreparedStatement stmt = metadatadb.prepareStatement(sql)) {

            stmt.setString(1, username);

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String user = rs.getString("username");
                String passwordHash = rs.getString("password_hash");

                return new User(user, passwordHash, new ArrayList<String>());
            }

            return null;
        }
    }
}