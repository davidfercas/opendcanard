package io.openduck.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordManager {

	public static void main(String[] args) {
		// TODO Auto-generated method stub

		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String rawPassword = "admin";
        String hashedPassword = encoder.encode(rawPassword);
        System.out.println(hashedPassword);
        System.out.println(encoder.matches(rawPassword, "$2a$10$mcPJoVjXDaDXsGT/2PZUxOy/ZgWM9AVsXN9Q5uDxsURBBocKr1LXi"));
		
	}

}
