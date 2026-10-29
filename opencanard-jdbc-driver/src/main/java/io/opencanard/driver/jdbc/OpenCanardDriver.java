package io.opencanard.driver.jdbc;

import java.sql.*;

public class OpenCanardDriver implements Driver {


    static {
        try {
            DriverManager.registerDriver(new OpenCanardDriver());
            DriverDiagnostics.debug("OpenCanard JDBC driver registered, loaded from {}",
                    DriverDiagnostics.locationOf(OpenCanardDriver.class));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Connection connect(String url, java.util.Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        DriverDiagnostics.configure(url, info);
        DriverDiagnostics.debug("connect({})", url);
        return new OpenCanardConnection(url, info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(OpenCanardConstants.URL_TYPE);
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
