package io.openduck.driver.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.Base64;

import org.apache.arrow.flight.*;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OpenDuckStatement implements Statement {
    private static final Logger logger = LogManager.getLogger(OpenDuckStatement.class);

    private final FlightClient client;
    private final BufferAllocator allocator;
    private final Connection connection;
    private final String username;
    private final String password;

    private ResultSet currentRs;
    private long updateCount = -1; // -1 indicates no update count or a result set is present
    private int queryTimeout = 0;
    private int maxRows = 0;
    private int fetchSize = 1024;
    private boolean closed = false;

    public OpenDuckStatement(Connection connection, FlightClient client, BufferAllocator allocator, String username, String password) {
        this.connection = connection;
        this.client = client;
        this.allocator = allocator;
        this.username = username;
        this.password = password;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        if (execute(sql)) {
            return currentRs;
        }
        throw new SQLException("SQL statement did not return a result set.");
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        execute(sql);
        return (int) (updateCount != -1 ? updateCount : 0);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkClosed();
        closeCurrentResults();

        try {
            CallOption auth = createBasicAuthOption(this.username, this.password);
            FlightDescriptor descriptor = FlightDescriptor.command(sql.getBytes(StandardCharsets.UTF_8));
            
            // 1. Get FlightInfo to see where data is and how many rows (if known)
            FlightInfo info = client.getInfo(descriptor, auth);
            
            // 2. Determine if this is a query (Result Set) or an update
            if (info.getEndpoints().isEmpty()) {
                // Likely a DDL/DML with no return data
                this.updateCount = info.getRecords() != -1 ? info.getRecords() : 0;
                this.currentRs = null;
                return false;
            } else {
                // 3. Instead of copying batches here, we pass the info to the ResultSet
                // The ResultSet will manage the FlightStream lifecycle.
                this.currentRs = new OpenDuckResultSet(this, info, auth);
                this.updateCount = -1;
                return true;
            }
        } catch (Exception e) {
            throw new SQLException("Error executing OpenDuck query: " + e.getMessage(), e);
        }
    }

    private void closeCurrentResults() throws SQLException {
        if (currentRs != null) {
            currentRs.close();
            currentRs = null;
        }
    }

    private void checkClosed() throws SQLException {
        if (closed) throw new SQLException("Statement is closed");
    }

    private CallOption createBasicAuthOption(String user, String pass) {
        if (user == null || pass == null) return new HeaderCallOption(new FlightCallHeaders());
        String combined = user + ":" + pass;
        String encoded = Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
        CallHeaders headers = new FlightCallHeaders();
        headers.insert("Authorization", "Basic " + encoded);
        return new HeaderCallOption(headers);
    }

    @Override
    public void close() throws SQLException {
        closeCurrentResults();
        this.closed = true;
    }

    // --- Standard JDBC Getters/Setters ---

    @Override public Connection getConnection() { return connection; }
    @Override public int getQueryTimeout() { return queryTimeout; }
    @Override public void setQueryTimeout(int seconds) { this.queryTimeout = seconds; }
    @Override public int getMaxRows() { return maxRows; }
    @Override public void setMaxRows(int max) { this.maxRows = max; }
    @Override public int getFetchSize() { return fetchSize; }
    @Override public void setFetchSize(int rows) { this.fetchSize = rows; }
    @Override public boolean isClosed() { return closed; }
    @Override public int getUpdateCount() { return (int) updateCount; }
    @Override public ResultSet getResultSet() { return currentRs; }

    @Override
    public boolean getMoreResults() throws SQLException {
        closeCurrentResults();
        return false;
    }

    // --- Boilerplate and Wrappers ---

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    // Explicitly throw Not Supported for complex JDBC features
    private SQLException unsupported(String feature) {
        return new SQLFeatureNotSupportedException("OpenDuck doesn't support " + feature);
    }

    @Override public void addBatch(String sql) throws SQLException { throw unsupported("batching"); }
    @Override public int[] executeBatch() throws SQLException { throw unsupported("batching"); }
    @Override public void cancel() throws SQLException { throw unsupported("query cancellation"); }

	@Override
	public int getMaxFieldSize() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setMaxFieldSize(int max) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setEscapeProcessing(boolean enable) throws SQLException {
		// TODO Auto-generated method stub
		
	}


	@Override
	public void setCursorName(String name) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}


    @Override
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != ResultSet.FETCH_FORWARD) {
            throw new SQLException("Only FETCH_FORWARD is supported");
        }
    }

    @Override
    public int getFetchDirection() {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public int getResultSetConcurrency() {
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public void clearBatch() throws SQLException {
        // No-op for now
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setPoolable(boolean poolable) {
        // Logic for connection pools
    }

    @Override
    public boolean isPoolable() {
        return false;
    }

    @Override
    public void closeOnCompletion() {
        // Optional JDBC 4.1 feature
    }

    @Override
    public boolean isCloseOnCompletion() {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return null; // DuckDB doesn't return generated keys via Flight this way
    }

    @Override
    public SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() {
        // No-op
    }

}