package io.opencanard.driver.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.memory.BufferAllocator;

/**
 * Prepared statement with client-side binding: the {@code ?} markers are replaced by SQL literals (see
 * {@link SqlParameters}) and the resulting SQL is sent like any other statement. The server does not
 * keep a prepared plan, so "prepare" only parses the markers.
 */
public class OpenCanardPreparedStatement extends OpenCanardStatement implements PreparedStatement {

	private final SqlParameters parameters;

	public OpenCanardPreparedStatement(String sql, Connection connection, FlightClient client,
			BufferAllocator allocator, String username, String password) throws SQLException {
		super(connection, client, allocator, username, password);
		try {
			this.parameters = new SqlParameters(sql);
		} catch (IllegalArgumentException e) {
			throw new SQLException(e.getMessage(), e);
		}
		DriverDiagnostics.debug("prepareStatement: {} parameter(s), sql={}", parameters.count(),
				DriverDiagnostics.abbreviate(parameters.template()));
	}

	// ---- execution ----

	@Override
	public ResultSet executeQuery() throws SQLException {
		return executeQuery(parameters.bind());
	}

	@Override
	public int executeUpdate() throws SQLException {
		return executeUpdate(parameters.bind());
	}

	@Override
	public boolean execute() throws SQLException {
		return execute(parameters.bind());
	}

	@Override
	public void clearParameters() throws SQLException {
		parameters.clear();
	}

	@Override
	public void addBatch() throws SQLException {
		throw new SQLFeatureNotSupportedException("OpenCanard doesn't support batching");
	}

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		// Known only once the statement has run: nothing is prepared on the server.
		ResultSet rs = getResultSet();
		return rs == null ? null : rs.getMetaData();
	}

	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		final int count = parameters.count();
		return new ParameterMetaData() {
			private void check(int p) throws SQLException {
				if (p < 1 || p > count) {
					throw new SQLException("Parameter index " + p + " is out of range");
				}
			}

			@Override
			public int getParameterCount() {
				return count;
			}

			@Override
			public int isNullable(int p) throws SQLException {
				check(p);
				return parameterNullableUnknown;
			}

			@Override
			public boolean isSigned(int p) throws SQLException {
				check(p);
				return false;
			}

			@Override
			public int getPrecision(int p) throws SQLException {
				check(p);
				return 0;
			}

			@Override
			public int getScale(int p) throws SQLException {
				check(p);
				return 0;
			}

			@Override
			public int getParameterType(int p) throws SQLException {
				check(p);
				return Types.OTHER;
			}

			@Override
			public String getParameterTypeName(int p) throws SQLException {
				check(p);
				return "UNKNOWN";
			}

			@Override
			public String getParameterClassName(int p) throws SQLException {
				check(p);
				return Object.class.getName();
			}

			@Override
			public int getParameterMode(int p) throws SQLException {
				check(p);
				return parameterModeIn;
			}

			@Override
			public <T> T unwrap(Class<T> iface) throws SQLException {
				if (iface.isInstance(this)) {
					return iface.cast(this);
				}
				throw new SQLException("Not a wrapper for " + iface.getName());
			}

			@Override
			public boolean isWrapperFor(Class<?> iface) {
				return iface.isInstance(this);
			}
		};
	}

	// ---- setters ----

	@Override
	public void setNull(int i, int sqlType) throws SQLException {
		parameters.set(i, null);
	}

	@Override
	public void setNull(int i, int sqlType, String typeName) throws SQLException {
		parameters.set(i, null);
	}

	@Override
	public void setBoolean(int i, boolean x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setByte(int i, byte x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setShort(int i, short x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setInt(int i, int x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setLong(int i, long x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setFloat(int i, float x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setDouble(int i, double x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setBigDecimal(int i, BigDecimal x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setString(int i, String x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setNString(int i, String x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setBytes(int i, byte[] x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setDate(int i, Date x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setDate(int i, Date x, Calendar cal) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setTime(int i, Time x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setTime(int i, Time x, Calendar cal) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setTimestamp(int i, Timestamp x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setTimestamp(int i, Timestamp x, Calendar cal) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setURL(int i, URL x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setObject(int i, Object x) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setObject(int i, Object x, int targetSqlType) throws SQLException {
		parameters.set(i, x);
	}

	@Override
	public void setObject(int i, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		parameters.set(i, x);
	}

	// ---- streams: read fully and bind as bytes / text ----

	private static byte[] readAll(InputStream in, long max) throws SQLException {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			long total = 0;
			int n;
			while ((max < 0 || total < max) && (n = in.read(buf, 0, (int) (max < 0 ? buf.length : Math.min(buf.length, max - total)))) > 0) {
				out.write(buf, 0, n);
				total += n;
			}
			return out.toByteArray();
		} catch (IOException e) {
			throw new SQLException("Cannot read the stream parameter: " + e.getMessage(), e);
		}
	}

	private static String readAll(Reader in, long max) throws SQLException {
		try {
			StringBuilder out = new StringBuilder();
			char[] buf = new char[8192];
			long total = 0;
			int n;
			while ((max < 0 || total < max) && (n = in.read(buf, 0, (int) (max < 0 ? buf.length : Math.min(buf.length, max - total)))) > 0) {
				out.append(buf, 0, n);
				total += n;
			}
			return out.toString();
		} catch (IOException e) {
			throw new SQLException("Cannot read the reader parameter: " + e.getMessage(), e);
		}
	}

	@Override
	public void setBinaryStream(int i, InputStream x, int length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setBinaryStream(int i, InputStream x, long length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setBinaryStream(int i, InputStream x) throws SQLException {
		parameters.set(i, readAll(x, -1));
	}

	@Override
	public void setAsciiStream(int i, InputStream x, int length) throws SQLException {
		parameters.set(i, new String(readAll(x, length), StandardCharsets.US_ASCII));
	}

	@Override
	public void setAsciiStream(int i, InputStream x, long length) throws SQLException {
		parameters.set(i, new String(readAll(x, length), StandardCharsets.US_ASCII));
	}

	@Override
	public void setAsciiStream(int i, InputStream x) throws SQLException {
		parameters.set(i, new String(readAll(x, -1), StandardCharsets.US_ASCII));
	}

	@Override
	public void setBlob(int i, InputStream x, long length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setBlob(int i, InputStream x) throws SQLException {
		parameters.set(i, readAll(x, -1));
	}

	@Override
	public void setCharacterStream(int i, Reader x, int length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setCharacterStream(int i, Reader x, long length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setCharacterStream(int i, Reader x) throws SQLException {
		parameters.set(i, readAll(x, -1));
	}

	@Override
	public void setNCharacterStream(int i, Reader x, long length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setNCharacterStream(int i, Reader x) throws SQLException {
		parameters.set(i, readAll(x, -1));
	}

	@Override
	public void setClob(int i, Reader x, long length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setClob(int i, Reader x) throws SQLException {
		parameters.set(i, readAll(x, -1));
	}

	@Override
	public void setNClob(int i, Reader x, long length) throws SQLException {
		parameters.set(i, readAll(x, length));
	}

	@Override
	public void setNClob(int i, Reader x) throws SQLException {
		parameters.set(i, readAll(x, -1));
	}

	@Override
	@SuppressWarnings("deprecation")
	public void setUnicodeStream(int i, InputStream x, int length) throws SQLException {
		parameters.set(i, new String(readAll(x, length), StandardCharsets.UTF_8));
	}

	// ---- not supported ----

	private SQLException unsupported(String what) {
		return new SQLFeatureNotSupportedException("OpenCanard doesn't support " + what + " parameters");
	}

	@Override
	public void setRef(int i, Ref x) throws SQLException {
		throw unsupported("Ref");
	}

	@Override
	public void setBlob(int i, Blob x) throws SQLException {
		throw unsupported("Blob");
	}

	@Override
	public void setClob(int i, Clob x) throws SQLException {
		throw unsupported("Clob");
	}

	@Override
	public void setNClob(int i, NClob x) throws SQLException {
		throw unsupported("NClob");
	}

	@Override
	public void setArray(int i, Array x) throws SQLException {
		throw unsupported("Array");
	}

	@Override
	public void setRowId(int i, RowId x) throws SQLException {
		throw unsupported("RowId");
	}

	@Override
	public void setSQLXML(int i, SQLXML x) throws SQLException {
		throw unsupported("SQLXML");
	}
}
