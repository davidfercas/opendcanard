package io.opencanard.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class UserRepository {

    private static final String DB_URL = "jdbc:duckdb:D:\\GitProjects\\opencanard\\opencanard-server\\data\\opencanard.duckdb";

    
    
    public UserRepository() {
		super();
		// TODO Auto-generated constructor stub
	}



	public User getUser(Connection metadatadb, String username) throws Exception {

        String sql = "SELECT username, password_hash, role FROM main.opencanard_users WHERE username = ?;";

        try (PreparedStatement stmt = metadatadb.prepareStatement(sql)) {

            stmt.setString(1, username);

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String user = rs.getString("username");
                String passwordHash = rs.getString("password_hash");
                String role = rs.getString("role");

                List<String> roles = new ArrayList<String>();
                roles.add(role);
                
                return new User(user, passwordHash, roles);
            }

            return null;
        }
    }

	/** Inserts a user; created_at is always set to now(). */
	public void insert(Connection metadatadb, String username, String passwordHash, String role) throws Exception {

		String sql = "INSERT INTO main.opencanard_users(username, password_hash, \"role\", created_at) "
				+ "VALUES (?, ?, ?, now());";

		try (PreparedStatement stmt = metadatadb.prepareStatement(sql)) {
			stmt.setString(1, username);
			stmt.setString(2, passwordHash);
			stmt.setString(3, role);
			stmt.executeUpdate();
		}
	}
}
