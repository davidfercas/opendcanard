package io.openduck.server;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;

import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfig;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfigBuilder;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.ActionType;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Schema;

public class OpenDuckFlightServer implements FlightProducer, AutoCloseable {

    private final Connection duckdb;
    private final FlightServer server;
    private final BufferAllocator allocator;


    public OpenDuckFlightServer(String host, int port, String duckdbPath, BufferAllocator allocator) throws Exception {
        // Embedded DuckDB
        this.duckdb = DriverManager.getConnection("jdbc:duckdb:" + duckdbPath);
        try (Statement stmt = this.duckdb.createStatement()) {
            
            if (!stmt.execute("INSTALL arrow;")) {
                int count = stmt.getUpdateCount();
                if (count == -1) {
                    System.out.println("Command executed successfully (no rows affected).");
                } else {
                    System.out.println("Rows affected: " + count);
                }
            }
            
            if (!stmt.execute("LOAD arrow;")) {
                int count = stmt.getUpdateCount();
                if (count == -1) {
                    System.out.println("Command executed successfully (no rows affected).");
                } else {
                    System.out.println("Rows affected: " + count);
                }
            }          
            
        }
        // Flight gRPC location
        Location location = Location.forGrpcInsecure(host, port);

        // Build server
        this.allocator = allocator;
        this.server = FlightServer.builder(this.allocator, location, this).build();
		
    }

    public void start() throws Exception {
        server.start();
        System.out.println("OpenDuck Server started at " + server.getLocation());
    }

    @Override
    public void close() throws Exception {
        server.close();
        duckdb.close();
    }

    // ============================
    // FlightProducer interface
    // ============================

    @Override
    public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
        // Arrow 18.3.0 has no getType(), check command directly
        if (descriptor.getCommand() == null) {
            throw CallStatus.INVALID_ARGUMENT
                    .withDescription("Only SQL command descriptors are supported")
                    .toRuntimeException();
        }

        String sql = new String(descriptor.getCommand(), StandardCharsets.UTF_8);

        try {
            Schema schema = DuckDBArrowUtil.getSchema(duckdb, sql);
            Ticket ticket = new Ticket(descriptor.getCommand());

            return new FlightInfo(
                    schema,
                    descriptor,
                    Collections.singletonList(new FlightEndpoint(ticket, server.getLocation())),
                    -1, -1
            );
        } catch (Exception e) {
            throw CallStatus.INTERNAL.withCause(e)
                    .withDescription(e.getMessage()).toRuntimeException();
        }
    }

    @Override
    public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
        String sql = new String(ticket.getBytes(), StandardCharsets.UTF_8);
        try {
            DuckDBArrowUtil.streamQuery(duckdb, sql, listener);
        } catch (Exception e) {
            listener.error(CallStatus.INTERNAL.withCause(e)
                    .withDescription(e.getMessage()).toRuntimeException());
        }
    }

    // ============================
    // Unused FlightProducer methods
    // ============================



    @Override
    public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
        throw new UnsupportedOperationException("listFlights not supported");
    }

    @Override
    public SchemaResult getSchema(CallContext context, FlightDescriptor descriptor) {
        // You must wrap the Schema in a SchemaResult
    	// Usually, the SQL command is sent in the 'cmd' field of the descriptor
        String sql = new String(descriptor.getCommand());
        
        try (Connection conn = this.duckdb) {
            Schema schema = getSchemaFromSql(conn, sql, this.allocator);
            return new SchemaResult(schema);
        } catch (SQLException e) {
            throw CallStatus.INTERNAL.withCause(e).withDescription("SQL Error").toRuntimeException();
        }
    }
//    @Override
//    public Schema getSchema(CallContext context, FlightDescriptor descriptor) {
//        throw new UnsupportedOperationException("getSchema not supported");
//    }

    @Override
    public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
        throw new UnsupportedOperationException("doAction not supported");
    }

    @Override
    public void listActions(CallContext context, StreamListener<ActionType> listener) {
        throw new UnsupportedOperationException("listActions not supported");
    }

    @Override
    public Runnable acceptPut(CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
        throw new UnsupportedOperationException("acceptPut not supported");
    }
    
    // TODO: Change to RootAllocator
    public Schema getSchemaFromSql(Connection conn, String sql, BufferAllocator allocator) throws SQLException {
        // 1. Prepare the statement but don't fetch all data yet
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            // 2. Get the Metadata from the database
            ResultSetMetaData metadata = stmt.getMetaData();
            
            // 3. Create a configuration for the conversion
            JdbcToArrowConfig config = new JdbcToArrowConfigBuilder()
                .setAllocator(allocator)
                .setReuseVectorSchemaRoot(true)
                .setTargetBatchSize(1024) // Smaller batches can sometimes avoid buffer overflows
                .build();
            
            // 4. Let Arrow translate JDBC types to Arrow types
            return JdbcToArrowUtils.jdbcToArrowSchema(metadata, config);
        }
    }
}
