package io.openduck.driver.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;


public class OpenDuckStatement implements Statement {

    private static final Logger logger = LogManager.getLogger(OpenDuckStatement.class);	
	
	private final FlightClient client;
	private final BufferAllocator allocator;
	private final Connection connection;
	private final String token;
	private final String username;
	private final String password;
	
	
    private ResultSet currentRs;

    private int queryTimeout = 0;
    private int maxRows = 100;
    private int fetchSize = 100;
    private boolean closed = false;
    
    /** Creates a CallOption for Basic Authentication (User/Pass) */
    private static CallOption createBasicAuthOption(String user, String pass) {
        String combined = user + ":" + pass;
        String encoded = Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
        
        CallHeaders headers = new FlightCallHeaders();
        headers.insert("Authorization", "Basic " + encoded);
        return new HeaderCallOption(headers);
    }    
    
	public OpenDuckStatement(Connection connection, FlightClient client, org.apache.arrow.memory.BufferAllocator allocator, String token, String username, String password) {
		this.connection = connection;
		this.client = client;
		this.allocator = allocator;
		this.token = token;
		this.username = username;
		this.password = password;
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		try {

			List<VectorSchemaRoot> batches = new ArrayList<>();

			FlightDescriptor descriptor = FlightDescriptor.command(sql.getBytes(StandardCharsets.UTF_8));

			// 1. Create a new headers container
			//FlightCallHeaders headers = new FlightCallHeaders();

			// 2. Add your custom headers (Key-Value pairs)
			// headers.insert("Authorization", "Bearer your-secret-token");
			//headers.insert("x-tenant-id", "openduck-01");

			// 3. Instantiate the CallOption using the headers
			//HeaderCallOption headerOption = new HeaderCallOption(headers);


//	        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
//	        String username = "admin";
//	        String rawPassword = "admin";
	        
	        CallOption basicAuth = createBasicAuthOption(this.username, this.password);
			
			
			FlightInfo info = client.getInfo(descriptor, basicAuth);

			List<FlightEndpoint> endpoints = info.getEndpoints();

			// THIS is where the snippet goes
			try (FlightStream stream = client.getStream(endpoints.get(0).getTicket(), basicAuth)) {
				while (stream.next()) {
			        VectorSchemaRoot root = stream.getRoot();
			        
			        // THIS is where you call deepCopyRoot
			        VectorSchemaRoot copy = deepCopyRoot(root, allocator);
			        
			        batches.add(copy);
				}
			} catch (Exception e) {
				throw new SQLException("Error reading Flight stream", e);
			}

			currentRs = new OpenDuckResultSet(batches); 
			return currentRs;

		} catch (Exception e) {
			throw new SQLException(e);
		}
	}

	public static VectorSchemaRoot deepCopyRoot(VectorSchemaRoot original, BufferAllocator allocator) {


	    VectorSchemaRoot copy =
	        VectorSchemaRoot.create(original.getSchema(), allocator);

	    VectorUnloader unloader = new VectorUnloader(original);
	    ArrowRecordBatch batch = unloader.getRecordBatch();

	    VectorLoader loader = new VectorLoader(copy);
	    loader.load(batch);

	    batch.close(); // VERY important

	    return copy;
	}

	@Override
	public void close() {
		try {
			if (this.currentRs!=null) {
				this.currentRs.close();
				this.closed = true;
			}
		} catch (SQLException e) {
			logger.error(e);
		}
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		currentRs = executeQuery(sql);
		return true;
	}

	private RuntimeException unsupported() {
		return new RuntimeException("Not implemented");
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
	public int executeUpdate(String sql) throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

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
	public int getMaxRows() throws SQLException {
		return this.maxRows;
	}

	@Override
	public void setMaxRows(int max) throws SQLException {
		this.maxRows = max;

	}

	@Override
	public void setEscapeProcessing(boolean enable) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getQueryTimeout() throws SQLException {
		return this.queryTimeout;
	}

	@Override
	public void setQueryTimeout(int seconds) throws SQLException {
		this.queryTimeout = seconds;

	}

	@Override
	public void cancel() throws SQLException {
		// TODO Auto-generated method stub

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
	public void setCursorName(String name) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public ResultSet getResultSet() throws SQLException {
		return currentRs;  
	}

	@Override
	public int getUpdateCount() throws SQLException {
		return 0;
	}

	@Override
	public boolean getMoreResults() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void setFetchDirection(int direction) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getFetchDirection() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setFetchSize(int rows) throws SQLException {
		this.fetchSize = rows;

	}

	@Override
	public int getFetchSize() throws SQLException {
		return this.fetchSize;
	}

	@Override
	public int getResultSetConcurrency() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getResultSetType() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void clearBatch() throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int[] executeBatch() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Connection getConnection() throws SQLException {
		return this.connection;
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		// TODO Auto-generated method stub
		return null;
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
	public int getResultSetHoldability() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean isClosed() throws SQLException {
		return this.closed;
	}

	@Override
	public void setPoolable(boolean poolable) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isPoolable() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void closeOnCompletion() throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isCloseOnCompletion() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

    // --- Delegation for DBeaver internal wrapper ---
    public Statement getOriginal() {
        return this; // IMPORTANT: avoid null
    }

}
