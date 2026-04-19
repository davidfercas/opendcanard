package io.openduck.driver.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OpenDuckConnection implements Connection {

	private static final Logger logger = LogManager.getLogger(OpenDuckConnection.class);

	private final BufferAllocator allocator;
	private final FlightClient client;
	private boolean closed = false;
	private final String token;
	private final String user;
	private final String password;

	// JDBC State
	private boolean readOnly = false;
	private int transactionIsolation = Connection.TRANSACTION_NONE; // DuckDB default logic
	private String schema = "main";
	private String catalog = "memory";

	public OpenDuckConnection(String url, Properties props) {
		this.allocator = new RootAllocator(Long.MAX_VALUE);

		String token = props.getProperty("token");
		this.token = token;
		this.user = props.getProperty("user");
		this.password = props.getProperty("password");

		String urlWithoutPrefix = url.replaceFirst("jdbc:openduck://", "");
		String[] hostPortAndDb = urlWithoutPrefix.split("/");
		String hostPort = hostPortAndDb[0]; // "[2001:db8::1]:3306" or "[2001:db8::1]"

		int port = OpenDuckConstants.DEFAULT_PORT;
		String host = "localhost";

		if (hostPort.startsWith("[") && hostPort.endsWith("]")) {
			// IPv6 without port
			host = hostPort.substring(1, hostPort.length() - 1);
		} else if (hostPort.startsWith("[") && hostPort.contains("]:")) {
			// IPv6 with port
			String[] parts = hostPort.split("]:");
			host = parts[0].substring(1);
			port = Integer.parseInt(parts[1]);
		} else {
			// IPv4 or hostname
			String[] parts = hostPort.split(":");
			host = parts[0];
			port = parts.length > 1 ? Integer.parseInt(parts[1]) : OpenDuckConstants.DEFAULT_PORT;
		}

		Location location = Location.forGrpcInsecure(host, port);
		this.client = FlightClient.builder(allocator, location).build();

	}

	@Override
	public Statement createStatement() throws SQLException {
		checkClosed();
		return new OpenDuckStatement(this, client, allocator, this.user, this.password);
		
	}

	@Override
	public void close() throws SQLException {
		if (closed)
			return;
		try {
			client.close();
			allocator.close();
			closed = true;
		} catch (Exception e) {
			throw new SQLException("Error closing OpenDuck resources", e);
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		checkClosed();
		return new OpenDuckDatabaseMetaData(this);
	}

	@Override
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		throw unsupported("prepareStatement");
	}

	@Override
	public CallableStatement prepareCall(String sql) throws SQLException {
		throw unsupported("prepareCall");
	}

	@Override
	public void commit() throws SQLException {
		/* No-op in auto-commit */ }

	@Override
	public void rollback() throws SQLException {
		throw unsupported("rollback");
	}

	@Override
	public boolean getAutoCommit() throws SQLException {
		return true;
	}

	@Override
	public void setAutoCommit(boolean autoCommit) throws SQLException {
		/* DuckDB is usually auto-commit by default */
	}

	private RuntimeException unsupported() {
		return new RuntimeException("Not implemented (OpenDuck phase 1)");
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this))
			return iface.cast(this);
		throw new SQLException("Not a wrapper for " + iface.getName());
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) {
		return iface.isInstance(this);
	}

	@Override
	public String nativeSQL(String sql) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void clearWarnings() throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
		return new OpenDuckStatement(this, client, allocator, this.user, this.password);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
			throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setHoldability(int holdability) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getHoldability() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Savepoint setSavepoint(String name) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void rollback(Savepoint savepoint) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
			throws SQLException {
		return new OpenDuckStatement(this, client, allocator, this.user, this.password);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Clob createClob() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Blob createBlob() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NClob createNClob() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isValid(int timeout) throws SQLException {
		if (closed)
			return false;
		try {
			// Simple "Ping" using an Action or a dummy FlightInfo request
			// If your server supports it, use a 'KeepAlive' action
			client.listActions().iterator().hasNext();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public void setClientInfo(String name, String value) throws SQLClientInfoException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setClientInfo(Properties properties) throws SQLClientInfoException {
		// TODO Auto-generated method stub

	}

	@Override
	public String getClientInfo(String name) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Properties getClientInfo() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void abort(Executor executor) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getNetworkTimeout() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	// --- State Management ---

	@Override
	public String getSchema() {
		return schema;
	}

	@Override
	public void setSchema(String schema) {
		this.schema = schema;
	}

	@Override
	public String getCatalog() {
		return catalog;
	}

	@Override
	public void setCatalog(String catalog) {
		this.catalog = catalog;
	}

	@Override
	public int getTransactionIsolation() {
		return transactionIsolation;
	}

	@Override
	public void setTransactionIsolation(int level) {
		this.transactionIsolation = level;
	}

	@Override
	public boolean isReadOnly() {
		return readOnly;
	}

	@Override
	public void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly;
	}
	// --- Helpers ---

	private void checkClosed() throws SQLException {
		if (closed)
			throw new SQLException("Connection is closed");
	}

	private SQLException unsupported(String method) {
		return new SQLFeatureNotSupportedException("OpenDuck does not yet support: " + method);
	}

	/**
	 * Returns the underlying FlightClient for use by Statements and ResultSets.
	 * This allows the ResultSet to open new streams lazily.
	 */
	public FlightClient getOriginalClient() throws SQLException {
	    if (closed) {
	        throw new SQLException("Cannot access FlightClient: Connection is closed.");
	    }
	    return this.client;
	}
	
}
