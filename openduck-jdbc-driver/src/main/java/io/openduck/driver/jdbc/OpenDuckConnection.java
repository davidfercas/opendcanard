package io.openduck.driver.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Result;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class OpenDuckConnection implements Connection {

	private static final Logger logger = LogManager.getLogger(OpenDuckConnection.class);
	
    private final BufferAllocator allocator;
    private final FlightClient client;
    private boolean closed = false;
    private final String token;
    private final String user;
    private final String password;

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
    public Statement createStatement() {
        return new OpenDuckStatement(this, client , allocator, this.token, this.user, this.password);
    }
    
    @Override
    public void close() {
        try {
			client.close();
			allocator.close();
	        closed = true;
		} catch (InterruptedException e) {
			logger.error(e);
		}
        
    }

    @Override public boolean isClosed() { return closed; }

    @Override public DatabaseMetaData getMetaData() {
        return new OpenDuckDatabaseMetaData(this);
    }

    // Stub the rest for now
    @Override public PreparedStatement prepareStatement(String sql) { throw unsupported(); }
    @Override public boolean getAutoCommit() { return true; }
    @Override public void setAutoCommit(boolean ac) {}
    @Override public void commit() {}
    @Override public void rollback() {}

    private RuntimeException unsupported() {
        return new RuntimeException("Not implemented (OpenDuck phase 1)");
    }

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public CallableStatement prepareCall(String sql) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String nativeSQL(String sql) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setReadOnly(boolean readOnly) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void setCatalog(String catalog) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String getCatalog() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setTransactionIsolation(int level) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public int getTransactionIsolation() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
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
		return new OpenDuckStatement(this, client, allocator, this.token, this.user, this.password);
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
		return new OpenDuckStatement(this, client, allocator, this.token, this.user, this.password);
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
		// TODO Auto-generated method stub
		return false;
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
	public void setSchema(String schema) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String getSchema() throws SQLException {
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


	
}
