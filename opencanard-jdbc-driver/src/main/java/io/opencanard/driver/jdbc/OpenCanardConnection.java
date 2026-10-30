package io.opencanard.driver.jdbc;

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

public class OpenCanardConnection implements Connection {


	private final BufferAllocator allocator;
	private final FlightClient client;
	private boolean closed = false;
	private final String token;
	private final String user;
	private final String password;
	private final String url;

	// JDBC State
	private boolean readOnly = false;
	private int transactionIsolation = Connection.TRANSACTION_NONE; // DuckDB default logic
	private String schema = "main";
	private String catalog = "memory";

	public OpenCanardConnection(String url, Properties props) throws SQLException {
		if (props == null) {
			props = new Properties();
		}
		DriverDiagnostics.configure(url, props);
		DriverDiagnostics.logEnvironmentOnce();

		this.url = url;
		this.token = props.getProperty("token");
		this.user = props.getProperty("user");
		this.password = props.getProperty("password");
		DriverDiagnostics.debug("Opening connection: url={}, user={}, password={}, token={}", url, this.user,
				this.password == null ? "not set" : "set", this.token == null ? "not set" : "set");

		// Drop a query string such as ?debug=true before parsing host and port
		String cleanUrl = url;
		int query = cleanUrl.indexOf('?');
		if (query >= 0) {
			cleanUrl = cleanUrl.substring(0, query);
		}

		String host = "localhost";
		int port = OpenCanardConstants.DEFAULT_PORT;
		try {
			String urlWithoutPrefix = cleanUrl.replaceFirst("jdbc:opencanard://", "");
			String[] hostPortAndDb = urlWithoutPrefix.split("/");
			String hostPort = hostPortAndDb[0]; // "[2001:db8::1]:3306" or "[2001:db8::1]"

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
				host = parts[0].isEmpty() ? "localhost" : parts[0];
				port = parts.length > 1 ? Integer.parseInt(parts[1]) : OpenCanardConstants.DEFAULT_PORT;
			}
		} catch (RuntimeException e) {
			throw new SQLException("Invalid OpenCanard URL '" + cleanUrl
					+ "', expected jdbc:opencanard://host[:port]: " + e.getMessage(), e);
		}
		DriverDiagnostics.debug("Target: host={}, port={}", host, port);

		BufferAllocator newAllocator = null;
		try {
			long start = System.nanoTime();
			newAllocator = new RootAllocator(Long.MAX_VALUE);
			DriverDiagnostics.debug("Arrow allocator created");
			Location location = Location.forGrpcInsecure(host, port);
			DriverDiagnostics.debug("Building the Flight client (this loads gRPC and Netty)...");
			FlightClient newClient = FlightClient.builder(newAllocator, location).build();
			this.allocator = newAllocator;
			this.client = newClient;
			DriverDiagnostics.debug("Flight client ready in {} ms", (System.nanoTime() - start) / 1_000_000);
		} catch (Throwable t) {
			if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
				throw (VirtualMachineError) t;
			}
			if (newAllocator != null) {
				try {
					newAllocator.close();
				} catch (Throwable ignored) {
				}
			}
			throw DriverDiagnostics.connectFailure(t, host, port);
		}
	}

	@Override
	public Statement createStatement() throws SQLException {
		checkClosed();
		return new OpenCanardStatement(this, client, allocator, this.user, this.password);
		
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
			throw new SQLException("Error closing OpenCanard resources", e);
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		checkClosed();
		return new OpenCanardDatabaseMetaData(this, this.url, this.user);
	}

	@Override
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		checkClosed();
		return new OpenCanardPreparedStatement(sql, this, client, allocator, this.user, this.password);
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
		return new RuntimeException("Not implemented (OpenCanard phase 1)");
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
		return sql;
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
		return new OpenCanardStatement(this, client, allocator, this.user, this.password);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
			throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		throw unsupported("prepareCall");
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		return new java.util.HashMap<>();
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
		return ResultSet.CLOSE_CURSORS_AT_COMMIT;
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		throw unsupported("setSavepoint");
	}

	@Override
	public Savepoint setSavepoint(String name) throws SQLException {
		throw unsupported("setSavepoint");
	}

	@Override
	public void rollback(Savepoint savepoint) throws SQLException {
		throw unsupported("savepoints");

	}

	@Override
	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		throw unsupported("savepoints");

	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
			throws SQLException {
		return new OpenCanardStatement(this, client, allocator, this.user, this.password);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		throw unsupported("prepareCall");
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public Clob createClob() throws SQLException {
		throw unsupported("createClob");
	}

	@Override
	public Blob createBlob() throws SQLException {
		throw unsupported("createBlob");
	}

	@Override
	public NClob createNClob() throws SQLException {
		throw unsupported("createNClob");
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		throw unsupported("createSQLXML");
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
		return new Properties();
	}

	@Override
	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		throw unsupported("createArrayOf");
	}

	@Override
	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		throw unsupported("createStruct");
	}

	@Override
	public void abort(Executor executor) throws SQLException {
		close();
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
		return new SQLFeatureNotSupportedException("OpenCanard does not yet support: " + method);
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
