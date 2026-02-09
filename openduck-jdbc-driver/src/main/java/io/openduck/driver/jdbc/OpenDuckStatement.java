package io.openduck.driver.jdbc;

import org.apache.arrow.flight.*;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.memory.RootAllocator;


public class OpenDuckStatement implements Statement {

	private final FlightClient client;
	private final BufferAllocator allocator;

	public OpenDuckStatement(FlightClient client, org.apache.arrow.memory.BufferAllocator allocator, String token) {
		this.client = client;
		this.allocator = allocator;
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		try {

			List<VectorSchemaRoot> batches = new ArrayList<>();

			FlightDescriptor descriptor = FlightDescriptor.command(sql.getBytes(StandardCharsets.UTF_8));

			// 1. Create a new headers container
			FlightCallHeaders headers = new FlightCallHeaders();

			// 2. Add your custom headers (Key-Value pairs)
			// headers.insert("Authorization", "Bearer your-secret-token");
			headers.insert("x-tenant-id", "openduck-01");

			// 3. Instantiate the CallOption using the headers
			HeaderCallOption headerOption = new HeaderCallOption(headers);

			FlightInfo info = client.getInfo(descriptor, headerOption);

			List<FlightEndpoint> endpoints = info.getEndpoints();

			// THIS is where the snippet goes
			try (FlightStream stream = client.getStream(endpoints.get(0).getTicket())) {
				while (stream.next()) {
			        VectorSchemaRoot root = stream.getRoot();
			        
			        // THIS is where you call deepCopyRoot
			        VectorSchemaRoot copy = deepCopyRoot(root, allocator);
			        
			        batches.add(copy);
				}
			} catch (Exception e) {
				throw new SQLException("Error reading Flight stream", e);
			}

			return new OpenDuckResultSet(batches);

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
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		executeQuery(sql);
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
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setMaxRows(int max) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setEscapeProcessing(boolean enable) throws SQLException {
		// TODO Auto-generated method stub

	}

	@Override
	public int getQueryTimeout() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setQueryTimeout(int seconds) throws SQLException {
		// TODO Auto-generated method stub

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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getUpdateCount() throws SQLException {
		// TODO Auto-generated method stub
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
		// TODO Auto-generated method stub

	}

	@Override
	public int getFetchSize() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
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
		// TODO Auto-generated method stub
		return null;
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
		// TODO Auto-generated method stub
		return false;
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
}
