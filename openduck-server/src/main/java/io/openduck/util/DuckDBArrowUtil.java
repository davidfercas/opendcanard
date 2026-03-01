package io.openduck.util;

import org.apache.arrow.adapter.jdbc.JdbcToArrowConfig;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfigBuilder;
import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBResultSet;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public final class DuckDBArrowUtil {

	private static final Logger logger = LogManager.getLogger(DuckDBArrowUtil.class);

	private DuckDBArrowUtil() {
	}

	// Get Arrow schema
	public static org.apache.arrow.vector.types.pojo.Schema getSchema(Connection conn, String sql) throws Exception {

		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

			JdbcToArrowConfig config = new JdbcToArrowConfigBuilder().setAllocator(new RootAllocator())
					.setTargetBatchSize(1024)
					// If the error persists, you may need a custom field mapper here
					.build();

			return JdbcToArrowUtils.jdbcToArrowSchema(rs.getMetaData(), config);
		} catch (Exception e) {
			logger.error(e);
			throw e;
		}
	}

	// Stream query to Flight
	public static void streamQuery(Connection conn, String sql, FlightProducer.ServerStreamListener listener)
			throws Exception {

		boolean completed = false;

		try (Statement stmt = conn.createStatement();
				// Cast the result set to DuckDBResultSet to access native Arrow methods
				DuckDBResultSet rs = (DuckDBResultSet) stmt.executeQuery(sql)) {

			// This is the "magic" method that uses the loaded 'arrow' extension
			BufferAllocator allocator = new RootAllocator();
			try (ArrowReader reader = (ArrowReader) rs.arrowExportStream(allocator, 1024)) {
			    VectorSchemaRoot root = reader.getVectorSchemaRoot();
			    listener.start(root);

			    while (!listener.isCancelled() && reader.loadNextBatch()) {
			        // Only send if batch has rows
			        if (root.getRowCount() > 0) {
			            listener.putNext();
			        }
			    }

			    if (!listener.isCancelled()) {
			        listener.completed();
			    }
			} catch (Exception e) {
			    logger.error("Flight server error", e);
			    listener.error(CallStatus.INTERNAL
			                   .withCause(e)
			                   .withDescription(e.getMessage())
			                   .toRuntimeException());
			}
		}
	}
}
