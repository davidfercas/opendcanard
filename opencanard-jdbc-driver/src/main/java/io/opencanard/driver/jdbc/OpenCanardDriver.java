package io.opencanard.driver.jdbc;

import java.sql.*;

public class OpenCanardDriver implements Driver {


    static {
        try {
            DriverManager.registerDriver(new OpenCanardDriver());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Connection connect(String url, java.util.Properties info) {
        if (!acceptsURL(url)) return null;
        return new OpenCanardConnection(url, info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url.startsWith(OpenCanardConstants.URL_TYPE);
    }

	@Override
	public DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
		return new DriverPropertyInfo[0];
	}

	@Override
	public int getMajorVersion() {
		return 0;
	}

	@Override
	public int getMinorVersion() {
		return 1;
	}

	@Override
	public boolean jdbcCompliant() {
		return false;
	}

	@Override
	public java.util.logging.Logger getParentLogger() {
		return null;
	}
}
