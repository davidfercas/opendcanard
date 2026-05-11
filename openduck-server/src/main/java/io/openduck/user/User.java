package io.openduck.user;

import java.util.List;

public class User {

    private String username;
    private String passwordHash;
    private List<String> roles;

    public User(String username, String passwordHash, List<String> roles) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.roles = roles;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPasswordHash() {
        return this.passwordHash;
    }
    
    public List<String> getRoles() {
    	return this.roles;
    }
}