package io.opencanard.flightsql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.adapter.jdbc.JdbcFieldInfo;
import org.apache.arrow.adapter.jdbc.JdbcParameterBinder;
import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.CancelFlightInfoRequest;
import org.apache.arrow.flight.CancelStatus;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.FlightSqlProducer;
import org.apache.arrow.flight.sql.SqlInfoBuilder;
import org.apache.arrow.flight.sql.impl.FlightSql.ActionClosePreparedStatementRequest;
import org.apache.arrow.flight.sql.impl.FlightSql.ActionCreatePreparedStatementRequest;
import org.apache.arrow.flight.sql.impl.FlightSql.ActionCreatePreparedStatementResult;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetCatalogs;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetCrossReference;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetDbSchemas;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetExportedKeys;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetImportedKeys;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetPrimaryKeys;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetSqlInfo;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetTableTypes;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetTables;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetXdbcTypeInfo;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandPreparedStatementQuery;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandPreparedStatementUpdate;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandStatementQuery;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandStatementUpdate;
import org.apache.arrow.flight.sql.impl.FlightSql.DoPutUpdateResult;
import org.apache.arrow.flight.sql.impl.FlightSql.SqlNullOrdering;
import org.apache.arrow.flight.sql.impl.FlightSql.SqlSupportedCaseSensitivity;
import org.apache.arrow.flight.sql.impl.FlightSql.SqlSupportedTransaction;
import org.apache.arrow.flight.sql.impl.FlightSql.TicketStatementQuery;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBResultSet;
import org.duckdb.DuckDBResultSetMetaData;
import org.duckdb.StatementReturnType;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import io.opencanard.console.SqlSplitter;
import io.opencanard.query.RunningQueries;
import io.opencanard.user.UserConnections;
import io.opencanard.util.DuckDBArrowUtil;

/**
 * The Arrow Flight SQL side of the Flight port, so standard clients connect without OpenCanard's own driver:
 * ADBC (Python, Go, C++, R, Java), the Apache Arrow Flight SQL JDBC driver, ODBC drivers, and the tools built on
 * them.
 * <p>
 * What it does:
 * <ul>
 * <li><b>Statements</b> ({@code CommandStatementQuery}) are executed when the client asks for their FlightInfo.
 * The open result is kept under a random handle until the client fetches it with the ticket, so the SQL runs once.
 * A statement that returns no rows (DDL, INSERT...) has already done its work: its FlightInfo has no endpoints and
 * carries the number of changed rows.</li>
 * <li><b>Updates</b> ({@code CommandStatementUpdate}, DoPut) run the SQL and answer the number of changed rows.</li>
 * <li><b>Prepared statements</b> keep a DuckDB prepared statement and its connection until the client closes them.
 * Parameters arrive as Arrow record batches and are bound with Arrow's {@link JdbcParameterBinder}. Several
 * parameter rows on an update run as one batch. A script with several statements cannot be prepared by DuckDB;
 * it is kept as plain SQL (without parameters) and run as a script.</li>
 * <li><b>Catalog metadata</b> (catalogs, schemas, tables with their Arrow schemas, table types, primary keys and
 * {@code GetSqlInfo}) comes from the DuckDB JDBC {@link DatabaseMetaData} of the caller's own database. Foreign
 * keys and type info are answered with empty results.</li>
 * <li><b>Cancel</b>: {@code CancelFlightInfo} stops a result that is being sent. Every statement is listed in the
 * running queries of the web console (source "Arrow Flight SQL") and can be killed there.</li>
 * </ul>
 * Every call runs on the database of the caller ({@link UserConnections}: admins on the metadata database, other
 * users on their registered file). Handles belong to the login that created them; another login gets
 * {@code NOT_FOUND}. Results and prepared statements that nobody uses for {@value #IDLE_MINUTES} minutes are
 * closed. Transactions are not supported (every statement auto-commits), which {@code GetSqlInfo} says.
 */
public class OpenCanardFlightSqlProducer implements FlightSqlProducer {

	private static final Logger logger = LogManager.getLogger(OpenCanardFlightSqlProducer.class);

	/** Results and prepared statements unused for this long are closed. */
	static final int IDLE_MINUTES = 10;

	/** Rows per Arrow batch read from DuckDB, as on the legacy Flight path. */
	private static final int BATCH_ROWS = 1024;

	private static final Schema EMPTY_SCHEMA = new Schema(Collections.emptyList());

	private final UserConnections userConnections;
	private final RunningQueries runningQueries;
	private final BufferAllocator allocator;
	private final SqlInfoBuilder sqlInfo;

	/** Executed statements whose rows the client has not fetched yet, by ticket handle. */
	private final Map<ByteString, OpenResult> results = new ConcurrentHashMap<>();
	/** Prepared statements the client has not closed yet, by handle. */
	private final Map<ByteString, Prepared> prepared = new ConcurrentHashMap<>();

	private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "flight-sql-sweeper");
		t.setDaemon(true);
		return t;
	});

	public OpenCanardFlightSqlProducer(UserConnections userConnections, RunningQueries runningQueries,
			BufferAllocator allocator, String serverVersion) {
		this.userConnections = userConnections;
		this.runningQueries = runningQueries;
		this.allocator = allocator;
		String arrowVersion = FlightSqlProducer.class.getPackage().getImplementationVersion();
		this.sqlInfo = new SqlInfoBuilder()
				.withFlightSqlServerName("OpenCanard")
				.withFlightSqlServerVersion(serverVersion)
				.withFlightSqlServerArrowVersion(arrowVersion == null ? "unknown" : arrowVersion)
				.withFlightSqlServerReadOnly(false)
				.withFlightSqlServerSql(true)
				.withFlightSqlServerSubstrait(false)
				.withFlightSqlServerTransaction(SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE)
				.withFlightSqlServerCancel(true)
				.withFlightSqlServerBulkIngestion(false)
				.withSqlIdentifierQuoteChar("\"")
				.withSqlIdentifierCase(SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
				.withSqlQuotedIdentifierCase(SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
				.withSqlNullOrdering(SqlNullOrdering.SQL_NULLS_SORTED_AT_END)
				.withSqlDdlCatalog(true)
				.withSqlDdlSchema(true)
				.withSqlDdlTable(true)
				.withSqlAllTablesAreSelectable(true)
				.withSqlSchemaTerm("schema")
				.withSqlCatalogTerm("database")
				.withSqlSearchStringEscape("\\")
				.withSqlSupportsColumnAliasing(true)
				.withSqlNullPlusNullIsNull(true)
				.withSqlSupportsExpressionsInOrderBy(true)
				.withSqlSupportsOrderByUnrelated(true)
				.withSqlSupportsLikeEscapeClause(true)
				.withSqlSupportsNonNullableColumns(true)
				.withSqlCorrelatedSubqueriesSupported(true)
				.withSqlBatchUpdatesSupported(true)
				.withSqlTransactionsSupported(false)
				.withSqlSavepointsSupported(false)
				.withSqlNamedParametersSupported(false);
		sweeper.scheduleWithFixedDelay(this::closeIdle, 1, 1, TimeUnit.MINUTES);
	}

	// ============================================================
	// Statements
	// ============================================================

	@Override
	public FlightInfo getFlightInfoStatement(CommandStatementQuery command, CallContext context,
			FlightDescriptor descriptor) {
		refuseTransaction(command.hasTransactionId());
		String login = context.peerIdentity();
		Connection conn = open(login);
		return execute(login, conn, true, command.getQuery(), descriptor);
	}

	@Override
	public SchemaResult getSchemaStatement(CommandStatementQuery command, CallContext context,
			FlightDescriptor descriptor) {
		refuseTransaction(command.hasTransactionId());
		try (Connection conn = open(context.peerIdentity());
				PreparedStatement ps = conn.prepareStatement(command.getQuery())) {
			return new SchemaResult(returnsRows(ps) ? datasetSchema(conn, ps, command.getQuery()) : EMPTY_SCHEMA);
		} catch (SQLException e) {
			throw status(e, null);
		}
	}

	@Override
	public void getStreamStatement(TicketStatementQuery ticket, CallContext context, ServerStreamListener listener) {
		ByteString handle = ticket.getStatementHandle();
		OpenResult result = results.get(handle);
		if (result == null || !result.login.equals(context.peerIdentity()) || !results.remove(handle, result)) {
			listener.error(CallStatus.NOT_FOUND
					.withDescription("Unknown or expired statement handle; run the statement again").toRuntimeException());
			return;
		}
		try {
			result.stream(listener);
		} finally {
			result.close();
		}
	}

	@Override
	public Runnable acceptPutStatement(CommandStatementUpdate command, CallContext context, FlightStream flightStream,
			StreamListener<PutResult> ackStream) {
		return () -> {
			RunningQueries.Query query = null;
			try {
				refuseTransaction(command.hasTransactionId());
				String login = context.peerIdentity();
				String sql = command.getQuery();
				long count;
				try (Connection conn = open(login); Statement stmt = conn.createStatement()) {
					query = runningQueries.start(login, RunningQueries.Source.FLIGHT_SQL, sql, null);
					query.attach(stmt);
					try {
						boolean first = stmt.execute(sql);
						count = DuckDBArrowUtil.updateCount(stmt, first);
					} finally {
						query.detach(stmt);
						runningQueries.finish(query); // before the connection closes
					}
				}
				sendUpdateCount(ackStream, count);
				ackStream.onCompleted();
			} catch (Exception e) {
				ackStream.onError(status(e, query));
			}
		};
	}

	/**
	 * Runs {@code sql} on {@code conn}. Rows are kept for the ticket of the returned FlightInfo; a statement
	 * without rows has done its work and its FlightInfo has no endpoints. {@code ownsConnection}: the connection is
	 * closed with the result (or right away when there are no rows to keep).
	 */
	private FlightInfo execute(String login, Connection conn, boolean ownsConnection, String sql,
			FlightDescriptor descriptor) {
		RunningQueries.Query query = runningQueries.start(login, RunningQueries.Source.FLIGHT_SQL, sql, null);
		Statement stmt = null;
		boolean kept = false;
		try {
			stmt = conn.createStatement();
			query.attach(stmt);
			boolean first = stmt.execute(sql);
			if (DuckDBArrowUtil.isUpdate(first, sql)) {
				long count = DuckDBArrowUtil.updateCount(stmt, first);
				logger.debug("[flight-sql] '{}' ran a statement without rows ({} changed)", login, count);
				return new FlightInfo(EMPTY_SCHEMA, descriptor, Collections.emptyList(), -1, count);
			}
			OpenResult result = OpenResult.open(this, login, ownsConnection ? conn : null, stmt,
					(DuckDBResultSet) stmt.getResultSet(), query);
			kept = true;
			return keep(result, descriptor);
		} catch (Exception e) {
			throw status(e, query);
		} finally {
			if (!kept) {
				query.detach(stmt);
				runningQueries.finish(query);
				closeQuietly(stmt);
				if (ownsConnection) {
					closeQuietly(conn);
				}
			}
		}
	}

	/** Registers an open result and answers the FlightInfo whose single endpoint fetches it. */
	private FlightInfo keep(OpenResult result, FlightDescriptor descriptor) {
		results.put(result.handle, result);
		Ticket ticket = new Ticket(
				Any.pack(TicketStatementQuery.newBuilder().setStatementHandle(result.handle).build()).toByteArray());
		// No location: the client fetches from the server it is already connected to
		return new FlightInfo(result.schema, descriptor, Collections.singletonList(new FlightEndpoint(ticket)), -1,
				-1);
	}

	// ============================================================
	// Prepared statements
	// ============================================================

	@Override
	public void createPreparedStatement(ActionCreatePreparedStatementRequest request, CallContext context,
			StreamListener<Result> listener) {
		Connection conn = null;
		try {
			refuseTransaction(request.hasTransactionId());
			String login = context.peerIdentity();
			String sql = request.getQuery();
			conn = open(login);
			Prepared p;
			try {
				PreparedStatement ps = conn.prepareStatement(sql);
				boolean rows = returnsRows(ps);
				p = new Prepared(newHandle(), login, conn, ps, sql, rows,
						rows ? datasetSchema(conn, ps, sql) : EMPTY_SCHEMA, parameterSchema(ps.getParameterMetaData()));
			} catch (SQLException e) {
				List<String> statements = SqlSplitter.split(sql);
				if (statements.size() < 2) {
					throw e;
				}
				// DuckDB prepares one statement at a time; a script runs as it is, without parameters. Its dataset
				// schema is unknown until it runs, so it is sent empty.
				boolean rows = !DuckDBArrowUtil.isUpdate(true, statements.get(statements.size() - 1));
				p = new Prepared(newHandle(), login, conn, null, sql, rows, EMPTY_SCHEMA, EMPTY_SCHEMA);
			}
			prepared.put(p.handle, p);
			conn = null; // owned by the prepared statement now
			ActionCreatePreparedStatementResult result = ActionCreatePreparedStatementResult.newBuilder()
					.setPreparedStatementHandle(p.handle)
					.setDatasetSchema(ByteString.copyFrom(serialize(p.datasetSchema)))
					.setParameterSchema(ByteString.copyFrom(serialize(p.parameterSchema)))
					.build();
			listener.onNext(new Result(Any.pack(result).toByteArray()));
			listener.onCompleted();
		} catch (Exception e) {
			closeQuietly(conn);
			listener.onError(status(e, null));
		}
	}

	@Override
	public void closePreparedStatement(ActionClosePreparedStatementRequest request, CallContext context,
			StreamListener<Result> listener) {
		ByteString handle = request.getPreparedStatementHandle();
		Prepared p = prepared.get(handle);
		if (p != null && p.login.equals(context.peerIdentity()) && prepared.remove(handle, p)) {
			p.close();
		}
		listener.onCompleted(); // closing twice, or something already expired, is not an error
	}

	@Override
	public FlightInfo getFlightInfoPreparedStatement(CommandPreparedStatementQuery command, CallContext context,
			FlightDescriptor descriptor) {
		Prepared p = preparedOf(command.getPreparedStatementHandle(), context);
		synchronized (p) {
			p.touch();
			p.closeActive();
			if (p.ps == null) {
				return execute(p.login, p.conn, false, p.sql, descriptor);
			}
			RunningQueries.Query query = runningQueries.start(p.login, RunningQueries.Source.FLIGHT_SQL, p.sql, null);
			boolean kept = false;
			try {
				query.attach(p.ps);
				if (p.pendingBatch) {
					// parameter rows for a statement without rows: one batch
					p.pendingBatch = false;
					return new FlightInfo(EMPTY_SCHEMA, descriptor, Collections.emptyList(), -1, sum(p.ps.executeLargeBatch()));
				}
				boolean first = p.ps.execute();
				if (!p.returnsRows || !first) {
					return new FlightInfo(EMPTY_SCHEMA, descriptor, Collections.emptyList(), -1,
							DuckDBArrowUtil.updateCount(p.ps, first));
				}
				OpenResult result = OpenResult.open(this, p.login, null, null, (DuckDBResultSet) p.ps.getResultSet(),
						query);
				kept = true;
				p.active = result.handle;
				return keep(result, descriptor);
			} catch (Exception e) {
				throw status(e, query);
			} finally {
				if (!kept) {
					query.detach(p.ps);
					runningQueries.finish(query);
				}
				p.touch();
			}
		}
	}

	@Override
	public SchemaResult getSchemaPreparedStatement(CommandPreparedStatementQuery command, CallContext context,
			FlightDescriptor descriptor) {
		return new SchemaResult(preparedOf(command.getPreparedStatementHandle(), context).datasetSchema);
	}

	@Override
	public void getStreamPreparedStatement(CommandPreparedStatementQuery command, CallContext context,
			ServerStreamListener listener) {
		// Our FlightInfo tickets are TicketStatementQuery; a client that builds this ticket itself runs it now
		try {
			FlightInfo info = getFlightInfoPreparedStatement(command, context, FlightDescriptor.command(new byte[0]));
			if (info.getEndpoints().isEmpty()) {
				try (VectorSchemaRoot empty = VectorSchemaRoot.create(EMPTY_SCHEMA, allocator)) {
					listener.start(empty);
					listener.completed();
				}
				return;
			}
			TicketStatementQuery ticket = Any.parseFrom(info.getEndpoints().get(0).getTicket().getBytes())
					.unpack(TicketStatementQuery.class);
			getStreamStatement(ticket, context, listener);
		} catch (FlightRuntimeException e) {
			listener.error(e);
		} catch (InvalidProtocolBufferException e) {
			listener.error(CallStatus.INTERNAL.withCause(e).withDescription(e.getMessage()).toRuntimeException());
		}
	}

	/** Parameters for a query (DoPut before GetFlightInfo). On an update the rows are kept as a batch. */
	@Override
	public Runnable acceptPutPreparedStatementQuery(CommandPreparedStatementQuery command, CallContext context,
			FlightStream flightStream, StreamListener<PutResult> ackStream) {
		return () -> {
			try {
				Prepared p = preparedOf(command.getPreparedStatementHandle(), context);
				synchronized (p) {
					p.touch();
					p.pendingBatch = false;
					if (p.ps != null) {
						p.ps.clearBatch();
					}
					int rows = 0;
					while (flightStream.next()) {
						VectorSchemaRoot root = flightStream.getRoot();
						if (root.getRowCount() == 0) {
							continue;
						}
						JdbcParameterBinder binder = binderFor(p, root);
						while (binder.next()) {
							rows++;
							if (!p.returnsRows) {
								p.ps.addBatch();
							}
						}
					}
					if (!p.returnsRows) {
						// DuckDB's addBatch clears the bound parameters, so even a single row runs as a batch
						p.pendingBatch = rows > 0;
					} else if (rows > 1) {
						logger.warn("[flight-sql] {} parameter rows for a query of '{}': only the last one is used", rows,
								p.login);
					}
				}
				ackStream.onCompleted();
			} catch (Exception e) {
				ackStream.onError(status(e, null));
			}
		};
	}

	@Override
	public Runnable acceptPutPreparedStatementUpdate(CommandPreparedStatementUpdate command, CallContext context,
			FlightStream flightStream, StreamListener<PutResult> ackStream) {
		return () -> {
			RunningQueries.Query query = null;
			try {
				Prepared p = preparedOf(command.getPreparedStatementHandle(), context);
				long total = 0;
				synchronized (p) {
					p.touch();
					p.closeActive();
					query = runningQueries.start(p.login, RunningQueries.Source.FLIGHT_SQL, p.sql, null);
					try {
						boolean batches = false;
						while (flightStream.next()) {
							batches = true;
							VectorSchemaRoot root = flightStream.getRoot();
							if (root.getRowCount() == 0) {
								total += runOnce(p, query);
								continue;
							}
							JdbcParameterBinder binder = binderFor(p, root);
							p.ps.clearBatch();
							while (binder.next()) {
								p.ps.addBatch();
							}
							query.attach(p.ps);
							try {
								total += sum(p.ps.executeLargeBatch());
							} finally {
								query.detach(p.ps);
							}
						}
						if (!batches) {
							total += runOnce(p, query);
						}
					} finally {
						runningQueries.finish(query);
						p.touch();
					}
				}
				sendUpdateCount(ackStream, total);
				ackStream.onCompleted();
			} catch (Exception e) {
				ackStream.onError(status(e, query));
			}
		};
	}

	/** Runs a prepared statement (or its script) once without new parameters; answers the changed rows. */
	private long runOnce(Prepared p, RunningQueries.Query query) throws SQLException {
		if (p.ps == null) {
			try (Statement stmt = p.conn.createStatement()) {
				query.attach(stmt);
				try {
					return DuckDBArrowUtil.updateCount(stmt, stmt.execute(p.sql));
				} finally {
					query.detach(stmt);
				}
			}
		}
		query.attach(p.ps);
		try {
			return DuckDBArrowUtil.updateCount(p.ps, p.ps.execute());
		} finally {
			query.detach(p.ps);
		}
	}

	private static JdbcParameterBinder binderFor(Prepared p, VectorSchemaRoot root) {
		if (p.ps == null) {
			throw CallStatus.INVALID_ARGUMENT
					.withDescription("A script with several statements cannot take parameters; prepare one statement")
					.toRuntimeException();
		}
		return JdbcParameterBinder.builder(p.ps, root).bindAll().build();
	}

	// ============================================================
	// Cancel
	// ============================================================

	@Override
	public void cancelFlightInfo(CancelFlightInfoRequest request, CallContext context,
			StreamListener<CancelStatus> listener) {
		String login = context.peerIdentity();
		boolean admin = userConnections.isAdmin(login);
		CancelStatus status = CancelStatus.NOT_CANCELLABLE;
		for (FlightEndpoint endpoint : request.getInfo().getEndpoints()) {
			try {
				Any any = Any.parseFrom(endpoint.getTicket().getBytes());
				if (!any.is(TicketStatementQuery.class)) {
					continue;
				}
				OpenResult result = results.get(any.unpack(TicketStatementQuery.class).getStatementHandle());
				if (result != null && runningQueries.cancel(result.query.getId(), login,
						admin) == RunningQueries.CancelResult.CANCELLED) {
					status = CancelStatus.CANCELLED;
				}
			} catch (InvalidProtocolBufferException e) {
				// not one of our tickets
			}
		}
		listener.onNext(status);
		listener.onCompleted();
	}

	// ============================================================
	// Catalog metadata
	// ============================================================

	@Override
	public FlightInfo getFlightInfoSqlInfo(CommandGetSqlInfo request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_SQL_INFO_SCHEMA);
	}

	@Override
	public void getStreamSqlInfo(CommandGetSqlInfo command, CallContext context, ServerStreamListener listener) {
		sqlInfo.send(command.getInfoList(), listener);
	}

	@Override
	public FlightInfo getFlightInfoTypeInfo(CommandGetXdbcTypeInfo request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_TYPE_INFO_SCHEMA);
	}

	@Override
	public void getStreamTypeInfo(CommandGetXdbcTypeInfo request, CallContext context, ServerStreamListener listener) {
		sendRows(listener, Schemas.GET_TYPE_INFO_SCHEMA, Collections.emptyList());
	}

	@Override
	public FlightInfo getFlightInfoCatalogs(CommandGetCatalogs request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_CATALOGS_SCHEMA);
	}

	@Override
	public void getStreamCatalogs(CallContext context, ServerStreamListener listener) {
		metadata(context, listener, Schemas.GET_CATALOGS_SCHEMA, md -> {
			List<Object[]> rows = new ArrayList<>();
			try (ResultSet rs = md.getCatalogs()) {
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("TABLE_CAT") });
				}
			}
			return rows;
		});
	}

	@Override
	public FlightInfo getFlightInfoSchemas(CommandGetDbSchemas request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_SCHEMAS_SCHEMA);
	}

	@Override
	public void getStreamSchemas(CommandGetDbSchemas command, CallContext context, ServerStreamListener listener) {
		String catalog = command.hasCatalog() ? command.getCatalog() : null;
		String pattern = command.hasDbSchemaFilterPattern() ? command.getDbSchemaFilterPattern() : null;
		metadata(context, listener, Schemas.GET_SCHEMAS_SCHEMA, md -> {
			List<Object[]> rows = new ArrayList<>();
			try (ResultSet rs = md.getSchemas(catalog, pattern)) {
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("TABLE_CATALOG"), rs.getString("TABLE_SCHEM") });
				}
			}
			return rows;
		});
	}

	@Override
	public FlightInfo getFlightInfoTables(CommandGetTables request, CallContext context, FlightDescriptor descriptor) {
		return infoFor(request, descriptor,
				request.getIncludeSchema() ? Schemas.GET_TABLES_SCHEMA : Schemas.GET_TABLES_SCHEMA_NO_SCHEMA);
	}

	@Override
	public void getStreamTables(CommandGetTables command, CallContext context, ServerStreamListener listener) {
		String catalog = command.hasCatalog() ? command.getCatalog() : null;
		String schemaPattern = command.hasDbSchemaFilterPattern() ? command.getDbSchemaFilterPattern() : null;
		String tablePattern = command.hasTableNameFilterPattern() ? command.getTableNameFilterPattern() : null;
		String[] types = command.getTableTypesCount() == 0 ? null
				: command.getTableTypesList().toArray(new String[0]);
		boolean includeSchema = command.getIncludeSchema();
		Schema schema = includeSchema ? Schemas.GET_TABLES_SCHEMA : Schemas.GET_TABLES_SCHEMA_NO_SCHEMA;
		metadata(context, listener, schema, md -> {
			Map<String, List<Field>> columns = includeSchema ? columnsOf(md, catalog, schemaPattern, tablePattern)
					: Collections.emptyMap();
			List<Object[]> rows = new ArrayList<>();
			try (ResultSet rs = md.getTables(catalog, schemaPattern, tablePattern, types)) {
				while (rs.next()) {
					String cat = rs.getString("TABLE_CAT");
					String sch = rs.getString("TABLE_SCHEM");
					String name = rs.getString("TABLE_NAME");
					String type = rs.getString("TABLE_TYPE");
					if (includeSchema) {
						List<Field> fields = columns.getOrDefault(tableKey(cat, sch, name), Collections.emptyList());
						rows.add(new Object[] { cat, sch, name, type, serialize(new Schema(fields)) });
					} else {
						rows.add(new Object[] { cat, sch, name, type });
					}
				}
			}
			return rows;
		});
	}

	/** The Arrow fields of every matching table, from one getColumns call, keyed by {@link #tableKey}. */
	private static Map<String, List<Field>> columnsOf(DatabaseMetaData md, String catalog, String schemaPattern,
			String tablePattern) throws SQLException {
		Map<String, List<Field>> out = new LinkedHashMap<>();
		try (ResultSet rs = md.getColumns(catalog, schemaPattern, tablePattern, null)) {
			while (rs.next()) {
				String key = tableKey(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"));
				boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
				ArrowType type = arrowType(rs.getInt("DATA_TYPE"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"));
				out.computeIfAbsent(key, k -> new ArrayList<>())
						.add(new Field(rs.getString("COLUMN_NAME"), new FieldType(nullable, type, null), null));
			}
		}
		return out;
	}

	private static String tableKey(String catalog, String schema, String table) {
		return catalog + '\u0000' + schema + '\u0000' + table;
	}

	@Override
	public FlightInfo getFlightInfoTableTypes(CommandGetTableTypes request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_TABLE_TYPES_SCHEMA);
	}

	@Override
	public void getStreamTableTypes(CallContext context, ServerStreamListener listener) {
		metadata(context, listener, Schemas.GET_TABLE_TYPES_SCHEMA, md -> {
			List<Object[]> rows = new ArrayList<>();
			try (ResultSet rs = md.getTableTypes()) {
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("TABLE_TYPE") });
				}
			}
			return rows;
		});
	}

	@Override
	public FlightInfo getFlightInfoPrimaryKeys(CommandGetPrimaryKeys request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_PRIMARY_KEYS_SCHEMA);
	}

	@Override
	public void getStreamPrimaryKeys(CommandGetPrimaryKeys command, CallContext context,
			ServerStreamListener listener) {
		String catalog = command.hasCatalog() ? command.getCatalog() : null;
		String schema = command.hasDbSchema() ? command.getDbSchema() : null;
		metadata(context, listener, Schemas.GET_PRIMARY_KEYS_SCHEMA, md -> {
			List<Object[]> rows = new ArrayList<>();
			try (ResultSet rs = md.getPrimaryKeys(catalog, schema, command.getTable())) {
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"),
							rs.getString("TABLE_NAME"), rs.getString("COLUMN_NAME"), rs.getInt("KEY_SEQ"),
							rs.getString("PK_NAME") });
				}
			}
			return rows;
		});
	}

	@Override
	public FlightInfo getFlightInfoExportedKeys(CommandGetExportedKeys request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_EXPORTED_KEYS_SCHEMA);
	}

	@Override
	public FlightInfo getFlightInfoImportedKeys(CommandGetImportedKeys request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_IMPORTED_KEYS_SCHEMA);
	}

	@Override
	public FlightInfo getFlightInfoCrossReference(CommandGetCrossReference request, CallContext context,
			FlightDescriptor descriptor) {
		return infoFor(request, descriptor, Schemas.GET_CROSS_REFERENCE_SCHEMA);
	}

	@Override
	public void getStreamExportedKeys(CommandGetExportedKeys command, CallContext context,
			ServerStreamListener listener) {
		sendRows(listener, Schemas.GET_EXPORTED_KEYS_SCHEMA, Collections.emptyList());
	}

	@Override
	public void getStreamImportedKeys(CommandGetImportedKeys command, CallContext context,
			ServerStreamListener listener) {
		sendRows(listener, Schemas.GET_IMPORTED_KEYS_SCHEMA, Collections.emptyList());
	}

	@Override
	public void getStreamCrossReference(CommandGetCrossReference command, CallContext context,
			ServerStreamListener listener) {
		sendRows(listener, Schemas.GET_CROSS_REFERENCE_SCHEMA, Collections.emptyList());
	}

	@Override
	public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
		listener.onCompleted(); // nothing is published as a named flight
	}

	private interface MetadataQuery {
		List<Object[]> rows(DatabaseMetaData md) throws SQLException;
	}

	/** Reads metadata on the caller's own database and sends it as one batch of {@code schema}. */
	private void metadata(CallContext context, ServerStreamListener listener, Schema schema, MetadataQuery query) {
		List<Object[]> rows;
		try (Connection conn = open(context.peerIdentity())) {
			rows = query.rows(conn.getMetaData());
		} catch (Exception e) {
			listener.error(status(e, null));
			return;
		}
		sendRows(listener, schema, rows);
	}

	/** Sends {@code rows} as one record batch; values are Strings, Integers or byte arrays, in field order. */
	private void sendRows(ServerStreamListener listener, Schema schema, List<Object[]> rows) {
		try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
			List<FieldVector> vectors = root.getFieldVectors();
			for (FieldVector v : vectors) {
				v.allocateNew();
			}
			for (int r = 0; r < rows.size(); r++) {
				Object[] row = rows.get(r);
				for (int c = 0; c < vectors.size(); c++) {
					setValue(vectors.get(c), r, c < row.length ? row[c] : null);
				}
			}
			for (FieldVector v : vectors) {
				v.setValueCount(rows.size());
			}
			root.setRowCount(rows.size());
			listener.start(root);
			listener.putNext();
			listener.completed();
		} catch (Exception e) {
			listener.error(status(e, null));
		}
	}

	private static void setValue(FieldVector vector, int index, Object value) {
		if (value == null) {
			vector.setNull(index);
		} else if (vector instanceof VarCharVector) {
			((VarCharVector) vector).setSafe(index, value.toString().getBytes(StandardCharsets.UTF_8));
		} else if (vector instanceof IntVector) {
			((IntVector) vector).setSafe(index, ((Number) value).intValue());
		} else if (vector instanceof VarBinaryVector) {
			((VarBinaryVector) vector).setSafe(index, (byte[]) value);
		} else {
			throw new IllegalArgumentException("No writer for " + vector.getClass().getSimpleName());
		}
	}

	private static FlightInfo infoFor(Message request, FlightDescriptor descriptor, Schema schema) {
		Ticket ticket = new Ticket(Any.pack(request).toByteArray());
		return new FlightInfo(schema, descriptor, Collections.singletonList(new FlightEndpoint(ticket)), -1, -1);
	}

	// ============================================================
	// Schemas and types
	// ============================================================

	/** True when the prepared statement returns rows (a query), false for DDL / DML without RETURNING. */
	private static boolean returnsRows(PreparedStatement ps) throws SQLException {
		ResultSetMetaData md = ps.getMetaData();
		if (md instanceof DuckDBResultSetMetaData) {
			return ((DuckDBResultSetMetaData) md).getReturnType() == StatementReturnType.QUERY_RESULT;
		}
		return md != null && md.getColumnCount() > 0;
	}

	/**
	 * The Arrow schema of a query's rows. Without parameters it is the exact schema DuckDB exports (the query
	 * wrapped in {@code LIMIT 0}, which does not read the data); otherwise, or if that fails, it is mapped from the
	 * JDBC metadata, where a type JDBC cannot describe becomes a string.
	 */
	private Schema datasetSchema(Connection conn, PreparedStatement ps, String sql) throws SQLException {
		if (ps.getParameterMetaData().getParameterCount() == 0) {
			String inner = sql.trim();
			while (inner.endsWith(";")) {
				inner = inner.substring(0, inner.length() - 1).trim();
			}
			String wrapped = "SELECT * FROM (\n" + inner + "\n) AS opencanard_schema LIMIT 0";
			try (Statement stmt = conn.createStatement();
					DuckDBResultSet rs = (DuckDBResultSet) stmt.executeQuery(wrapped);
					BufferAllocator child = allocator.newChildAllocator("flight-sql-schema", 0, Long.MAX_VALUE);
					ArrowReader reader = (ArrowReader) rs.arrowExportStream(child, 1)) {
				return reader.getVectorSchemaRoot().getSchema();
			} catch (Exception e) {
				logger.debug("[flight-sql] exact schema not available ({}), using the JDBC metadata", e.getMessage());
			}
		}
		ResultSetMetaData md = ps.getMetaData();
		List<Field> fields = new ArrayList<>();
		for (int i = 1; i <= md.getColumnCount(); i++) {
			ArrowType type = arrowType(md.getColumnType(i), md.getPrecision(i), md.getScale(i));
			fields.add(new Field(md.getColumnLabel(i), FieldType.nullable(type), null));
		}
		return new Schema(fields);
	}

	/** One nullable field per parameter, typed as DuckDB inferred it ({@code parameter_1}, ...). */
	private static Schema parameterSchema(ParameterMetaData md) throws SQLException {
		List<Field> fields = new ArrayList<>();
		int count = md == null ? 0 : md.getParameterCount();
		for (int i = 1; i <= count; i++) {
			int type;
			int precision = 0;
			int scale = 0;
			try {
				type = md.getParameterType(i);
				precision = md.getPrecision(i);
				scale = md.getScale(i);
			} catch (SQLException e) {
				type = Types.VARCHAR;
			}
			fields.add(new Field("parameter_" + i, FieldType.nullable(arrowType(type, precision, scale)), null));
		}
		return new Schema(fields);
	}

	/**
	 * Maps a JDBC type to Arrow. Types without a complete Arrow equivalent here (lists and structs need their
	 * child types, JDBC's OTHER is anything) become strings.
	 */
	static ArrowType arrowType(int jdbcType, int precision, int scale) {
		switch (jdbcType) {
		case Types.ARRAY:
		case Types.STRUCT:
		case Types.OTHER:
		case Types.JAVA_OBJECT:
			return new ArrowType.Utf8();
		case Types.DECIMAL:
		case Types.NUMERIC:
			if (precision <= 0) {
				return new ArrowType.Utf8();
			}
			break;
		default:
			break;
		}
		try {
			return JdbcToArrowUtils.getArrowTypeFromJdbcType(new JdbcFieldInfo(jdbcType, precision, scale), null);
		} catch (RuntimeException e) {
			return new ArrowType.Utf8();
		}
	}

	/** A schema as an Arrow IPC message, the form Flight SQL uses inside its messages. */
	static byte[] serialize(Schema schema) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			MessageSerializer.serialize(new WriteChannel(Channels.newChannel(out)), schema);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot serialize a schema", e);
		}
		return out.toByteArray();
	}

	// ============================================================
	// Helpers
	// ============================================================

	private Connection open(String login) {
		try {
			return userConnections.open(login);
		} catch (UserConnections.NoDatabaseException e) {
			throw CallStatus.UNAUTHORIZED.withDescription(e.getMessage()).toRuntimeException();
		} catch (SQLException e) {
			throw status(e, null);
		}
	}

	private Prepared preparedOf(ByteString handle, CallContext context) {
		Prepared p = prepared.get(handle);
		if (p == null || !p.login.equals(context.peerIdentity())) {
			throw CallStatus.NOT_FOUND
					.withDescription("Unknown or expired prepared statement; prepare it again").toRuntimeException();
		}
		return p;
	}

	private static void refuseTransaction(boolean hasTransactionId) {
		if (hasTransactionId) {
			throw CallStatus.INVALID_ARGUMENT
					.withDescription("Transactions are not supported: every statement commits on its own")
					.toRuntimeException();
		}
	}

	private void sendUpdateCount(StreamListener<PutResult> ackStream, long count) {
		byte[] bytes = DoPutUpdateResult.newBuilder().setRecordCount(count).build().toByteArray();
		try (ArrowBuf buffer = allocator.buffer(bytes.length)) {
			buffer.writeBytes(bytes);
			ackStream.onNext(PutResult.metadata(buffer));
		}
	}

	private static long sum(long[] counts) {
		long total = 0;
		for (long c : counts) {
			total += Math.max(c, 0);
		}
		return total;
	}

	private static ByteString newHandle() {
		return ByteString.copyFromUtf8(UUID.randomUUID().toString());
	}

	/** The Flight error to send for {@code e}; a cancelled query becomes CANCELLED with a clear message. */
	static FlightRuntimeException status(Exception e, RunningQueries.Query query) {
		if (e instanceof FlightRuntimeException) {
			return (FlightRuntimeException) e;
		}
		if (query != null && query.isCancelled()) {
			return CallStatus.CANCELLED.withDescription(query.cancelledException().getMessage()).toRuntimeException();
		}
		if (e instanceof SQLException) {
			SQLException sql = (SQLException) e;
			if ("57014".equals(sql.getSQLState())) {
				return CallStatus.CANCELLED.withDescription(sql.getMessage()).toRuntimeException();
			}
			// DuckDB's errors are about the SQL (syntax, unknown table, constraint...): the client's to fix
			return CallStatus.INVALID_ARGUMENT.withDescription(sql.getMessage()).withCause(e).toRuntimeException();
		}
		logger.error("[flight-sql] unexpected error", e);
		return CallStatus.INTERNAL.withDescription(String.valueOf(e.getMessage())).withCause(e).toRuntimeException();
	}

	static void closeQuietly(AutoCloseable c) {
		if (c == null) {
			return;
		}
		try {
			c.close();
		} catch (Exception e) {
			logger.debug("[flight-sql] closing {} failed: {}", c.getClass().getSimpleName(), e.getMessage());
		}
	}

	/** Closes results and prepared statements nobody used for {@link #IDLE_MINUTES} minutes. */
	void closeIdle() {
		long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(IDLE_MINUTES);
		results.forEach((handle, r) -> {
			if (r.lastUsed < cutoff && results.remove(handle, r)) {
				logger.info("[flight-sql] closing a result of '{}' that was never fetched", r.login);
				r.close();
			}
		});
		prepared.forEach((handle, p) -> {
			synchronized (p) { // waits for a call that is using it, which also refreshes lastUsed
				if (p.lastUsed < cutoff && prepared.remove(handle, p)) {
					logger.info("[flight-sql] closing an idle prepared statement of '{}'", p.login);
					p.close();
				}
			}
		});
	}

	/** Open results and prepared statements, for tests and logs. */
	int openHandles() {
		return results.size() + prepared.size();
	}

	@Override
	public void close() {
		sweeper.shutdownNow();
		results.values().forEach(OpenResult::close);
		results.clear();
		prepared.values().forEach(Prepared::close);
		prepared.clear();
	}

	// ============================================================
	// State kept between calls
	// ============================================================

	/** A DuckDB prepared statement (or a script, when {@code ps} is null) and the connection it lives on. */
	private final class Prepared {
		final ByteString handle;
		final String login;
		final Connection conn;
		final PreparedStatement ps;
		final String sql;
		final boolean returnsRows;
		final Schema datasetSchema;
		final Schema parameterSchema;
		volatile long lastUsed = System.currentTimeMillis();
		/** Several parameter rows were added with addBatch and run at the next execution. */
		boolean pendingBatch;
		/** Handle of the result of the last execution, while it has not been fetched. */
		ByteString active;

		Prepared(ByteString handle, String login, Connection conn, PreparedStatement ps, String sql,
				boolean returnsRows, Schema datasetSchema, Schema parameterSchema) {
			this.handle = handle;
			this.login = login;
			this.conn = conn;
			this.ps = ps;
			this.sql = sql;
			this.returnsRows = returnsRows;
			this.datasetSchema = datasetSchema;
			this.parameterSchema = parameterSchema;
		}

		void touch() {
			lastUsed = System.currentTimeMillis();
		}

		/** Drops the unfetched result of the previous execution: running the statement again replaces it. */
		void closeActive() {
			if (active != null) {
				OpenResult previous = results.remove(active);
				if (previous != null) {
					previous.close();
				}
				active = null;
			}
		}

		synchronized void close() {
			closeActive();
			closeQuietly(ps);
			closeQuietly(conn);
		}
	}

	/**
	 * An executed statement whose rows wait for the client's DoGet. Holds the Arrow reader over DuckDB's result and
	 * whatever must be closed after it: the statement and the connection when they belong to this result only.
	 */
	private static final class OpenResult implements AutoCloseable {
		final ByteString handle = newHandle();
		final String login;
		final Connection conn;
		final Statement stmt;
		final ResultSet rs;
		final BufferAllocator child;
		final ArrowReader reader;
		final Schema schema;
		final RunningQueries.Query query;
		final RunningQueries runningQueries;
		volatile long lastUsed = System.currentTimeMillis();
		private boolean closed;

		private OpenResult(String login, Connection conn, Statement stmt, ResultSet rs, BufferAllocator child,
				ArrowReader reader, Schema schema, RunningQueries.Query query, RunningQueries runningQueries) {
			this.login = login;
			this.conn = conn;
			this.stmt = stmt;
			this.rs = rs;
			this.child = child;
			this.reader = reader;
			this.schema = schema;
			this.query = query;
			this.runningQueries = runningQueries;
		}

		/**
		 * Wraps a DuckDB result. {@code conn} and {@code stmt} are closed with it when given (null: someone else
		 * owns them). On failure nothing is kept open but what the caller passed.
		 */
		static OpenResult open(OpenCanardFlightSqlProducer producer, String login, Connection conn, Statement stmt,
				DuckDBResultSet rs, RunningQueries.Query query) throws SQLException, IOException {
			BufferAllocator child = producer.allocator.newChildAllocator("flight-sql-result", 0, Long.MAX_VALUE);
			ArrowReader reader = null;
			try {
				reader = (ArrowReader) rs.arrowExportStream(child, BATCH_ROWS);
				Schema schema = reader.getVectorSchemaRoot().getSchema();
				return new OpenResult(login, conn, stmt, rs, child, reader, schema, query, producer.runningQueries);
			} catch (SQLException | IOException | RuntimeException e) {
				closeQuietly(reader);
				closeQuietly(rs);
				closeQuietly(child);
				throw e;
			}
		}

		/** Sends every batch to the client. Errors go to the listener. */
		synchronized void stream(ServerStreamListener listener) {
			lastUsed = System.currentTimeMillis();
			try {
				VectorSchemaRoot root = reader.getVectorSchemaRoot();
				listener.start(root);
				while (!listener.isCancelled() && !query.isCancelled() && reader.loadNextBatch()) {
					if (root.getRowCount() > 0) {
						listener.putNext();
					}
				}
				if (query.isCancelled()) {
					listener.error(CallStatus.CANCELLED.withDescription(query.cancelledException().getMessage())
							.toRuntimeException());
				} else if (!listener.isCancelled()) {
					listener.completed();
				}
			} catch (Exception e) {
				listener.error(status(e, query));
			}
		}

		@Override
		public synchronized void close() {
			if (closed) {
				return;
			}
			closed = true;
			closeQuietly(reader);
			closeQuietly(rs);
			if (stmt != null) {
				query.detach(stmt);
			}
			runningQueries.finish(query); // before the connection is closed: a cancel must not reach a closed one
			closeQuietly(stmt);
			closeQuietly(conn);
			closeQuietly(child);
		}
	}
}
