package io.opencanard.driver.jdbc;

import org.apache.arrow.flight.*;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.util.*;

public class OpenCanardResultSet implements ResultSet {

    private final Statement statement;
    private final FlightClient client;
    private final List<FlightEndpoint> endpoints;
    private final CallOption authOption;
    
    private FlightStream currentStream;
    private VectorSchemaRoot currentBatch;
    private int endpointIndex = 0;
    private int rowIndex = -1;
    private boolean closed = false;
    private boolean lastWasNull = false;
    private int rowNumber = 0;
    private boolean afterLast = false;

    public OpenCanardResultSet(Statement statement, FlightInfo info, CallOption auth) throws SQLException {
        this.statement = statement;
        this.client = ((OpenCanardConnection)statement.getConnection()).getOriginalClient(); 
        this.endpoints = info.getEndpoints();
        this.authOption = auth;
        
        // Initialize the first stream
        moveToNextStream();
        
        // We need to "prime" the currentBatch to get the schema, 
        // even if we haven't started iterating with next() yet.
        if (currentStream != null && currentStream.next()) {
            this.currentBatch = currentStream.getRoot();
            DriverDiagnostics.debug("First batch received: {} rows, {} columns", currentBatch.getRowCount(),
                    currentBatch.getFieldVectors().size());
            // Since we just physically moved the stream to the first batch,
            // we set rowIndex to -1 so that the FIRST call to next() 
            // correctly points to index 0.
            this.rowIndex = -1; 
        } else if (currentStream != null) {
            // No batch at all (empty result): the stream root still carries the schema,
            // so getMetaData() works and next() simply returns false.
            try {
                this.currentBatch = currentStream.getRoot();
            } catch (RuntimeException e) {
                DriverDiagnostics.debugError("Empty result without schema", e);
            }
            DriverDiagnostics.debug("Empty result set");
        }
        
    }

    private boolean moveToNextStream() throws SQLException {
        try {
            if (currentStream != null) currentStream.close();
            if (endpointIndex >= endpoints.size()) return false;

            Ticket ticket = endpoints.get(endpointIndex++).getTicket();
            DriverDiagnostics.debug("Opening Flight stream {} of {}", endpointIndex, endpoints.size());
            currentStream = client.getStream(ticket, authOption);
            return true;
        } catch (Exception e) {
            DriverDiagnostics.debugError("Failed to open Flight stream " + endpointIndex, e);
            throw new SQLException("Failed to open Flight stream", e);
        }
    }

    @Override
    public boolean next() throws SQLException {
        checkClosed();
        if (afterLast) return false;
        boolean has = nextRow();
        if (has) rowNumber++; else afterLast = true;
        return has;
    }

    private boolean nextRow() throws SQLException {
        
        if (currentStream == null) return false;

        rowIndex++;
        
        // Check if we need to load the next batch in the current stream
        if (currentBatch == null || rowIndex >= currentBatch.getRowCount()) {
            if (currentStream.next()) {
                currentBatch = currentStream.getRoot();
                rowIndex = 0;
                // Recursive check for empty batches
                if (currentBatch.getRowCount() == 0) return nextRow(); 
            } else {
                // Current stream exhausted, try next endpoint/stream
                if (moveToNextStream()) {
                    return nextRow();
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        checkClosed();
        if (currentBatch == null || rowIndex < 0 || rowIndex >= currentBatch.getRowCount()) {
            throw new SQLException("Cursor out of bounds: call next() before reading values");
        }
        if (columnIndex < 1 || columnIndex > currentBatch.getFieldVectors().size()) {
            throw new SQLException("Column index " + columnIndex + " is out of range, the result has "
                    + currentBatch.getFieldVectors().size() + " column(s)");
        }

        FieldVector vector = currentBatch.getVector(columnIndex - 1);
        if (vector.isNull(rowIndex)) {
            lastWasNull = true;
            return null;
        }
        lastWasNull = false;
        try {
            return toJdbcValue(vector.getField().getType(), vector.getObject(rowIndex));
        } catch (RuntimeException e) {
            throw new SQLException("Error obtaining the value of column " + columnIndex + " ("
                    + vector.getField().getName() + ", " + vector.getField().getType() + "): " + e, e);
        }
    }

    /**
     * Arrow's getObject returns Arrow's own types or raw numbers (Text for VARCHAR, an int of days for DATE,
     * a long of micros for TIME and for timestamps with a time zone). JDBC clients expect String,
     * java.sql.Date, java.sql.Time and java.sql.Timestamp.
     */
    private static Object toJdbcValue(ArrowType type, Object value) throws SQLException {
        if (value instanceof org.apache.arrow.vector.util.Text) {
            return value.toString();
        }
        if (type instanceof ArrowType.Timestamp) {
            String unit = ((ArrowType.Timestamp) type).getUnit().name();
            if (value instanceof Long) {
                return Timestamp.from(ValueConverter.instant((Long) value, unit));
            }
            return ValueConverter.toTimestamp(value);
        }
        if (type instanceof ArrowType.Date) {
            if (value instanceof Integer) {
                return Date.valueOf(java.time.LocalDate.ofEpochDay((Integer) value));
            }
            if (value instanceof Long) {
                return Date.valueOf(java.time.LocalDate.ofEpochDay(Math.floorDiv((Long) value, 86_400_000L)));
            }
            return ValueConverter.toDate(value);
        }
        if (type instanceof ArrowType.Time) {
            String unit = ((ArrowType.Time) type).getUnit().name();
            if (value instanceof Number) {
                return Time.valueOf(ValueConverter.timeOfDay(((Number) value).longValue(), unit));
            }
            return ValueConverter.toTime(value);
        }
        return value;
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public boolean wasNull() { return lastWasNull; }

    // --- Type Mapping Bridge ---

    @Override public String getString(int i) throws SQLException { return ValueConverter.toString(getObject(i)); }
    @Override public boolean getBoolean(int i) throws SQLException { return ValueConverter.toBoolean(getObject(i)); }
    @Override public byte getByte(int i) throws SQLException { return ValueConverter.toByte(getObject(i)); }
    @Override public short getShort(int i) throws SQLException { return ValueConverter.toShort(getObject(i)); }
    @Override public int getInt(int i) throws SQLException { return ValueConverter.toInt(getObject(i)); }
    @Override public long getLong(int i) throws SQLException { return ValueConverter.toLong(getObject(i)); }
    @Override public float getFloat(int i) throws SQLException { return ValueConverter.toFloat(getObject(i)); }
    @Override public double getDouble(int i) throws SQLException { return ValueConverter.toDouble(getObject(i)); }
    @Override public BigDecimal getBigDecimal(int i) throws SQLException { return ValueConverter.toBigDecimal(getObject(i)); }
    @Override public byte[] getBytes(int i) throws SQLException { return ValueConverter.toBytes(getObject(i)); }
    @Override public Date getDate(int i) throws SQLException { return ValueConverter.toDate(getObject(i)); }
    @Override public Time getTime(int i) throws SQLException { return ValueConverter.toTime(getObject(i)); }
    @Override public Timestamp getTimestamp(int i) throws SQLException { return ValueConverter.toTimestamp(getObject(i)); }
    @Override public java.io.Reader getCharacterStream(int i) throws SQLException {
        String v = getString(i);
        return v == null ? null : new java.io.StringReader(v);
    }
    @Override public java.io.InputStream getBinaryStream(int i) throws SQLException {
        byte[] v = getBytes(i);
        return v == null ? null : new java.io.ByteArrayInputStream(v);
    }
    @Override public java.io.InputStream getAsciiStream(int i) throws SQLException {
        String v = getString(i);
        return v == null ? null : new java.io.ByteArrayInputStream(v.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Override public java.io.InputStream getUnicodeStream(int i) throws SQLException {
        String v = getString(i);
        return v == null ? null : new java.io.ByteArrayInputStream(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Override public BigDecimal getBigDecimal(int i, int scale) throws SQLException {
        BigDecimal v = getBigDecimal(i);
        return v == null ? null : v.setScale(scale, java.math.RoundingMode.HALF_UP);
    }
    @Override public String getNString(int i) throws SQLException { return getString(i); }
    @Override public java.io.Reader getNCharacterStream(int i) throws SQLException { return getCharacterStream(i); }
    @Override public Date getDate(int i, Calendar cal) throws SQLException { return getDate(i); }
    @Override public Time getTime(int i, Calendar cal) throws SQLException { return getTime(i); }
    @Override public Timestamp getTimestamp(int i, Calendar cal) throws SQLException { return getTimestamp(i); }
    @Override public Object getObject(int i, Map<String, Class<?>> map) throws SQLException { return getObject(i); }
    @Override public URL getURL(int i) throws SQLException {
        String v = getString(i);
        try {
            return v == null ? null : new URL(v);
        } catch (java.net.MalformedURLException e) {
            throw new SQLException("Cannot convert '" + v + "' to a URL", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(int i, Class<T> type) throws SQLException {
        if (type == null) throw new SQLException("The target type is null");
        Object o = getObject(i);
        if (o == null) return null;
        Object r;
        if (type == Object.class || type.isInstance(o) && type != Number.class) r = o;
        else if (type == String.class) r = ValueConverter.toString(o);
        else if (type == Integer.class) r = ValueConverter.toInt(o);
        else if (type == Long.class) r = ValueConverter.toLong(o);
        else if (type == Short.class) r = ValueConverter.toShort(o);
        else if (type == Byte.class) r = ValueConverter.toByte(o);
        else if (type == Double.class) r = ValueConverter.toDouble(o);
        else if (type == Float.class) r = ValueConverter.toFloat(o);
        else if (type == Boolean.class) r = ValueConverter.toBoolean(o);
        else if (type == BigDecimal.class) r = ValueConverter.toBigDecimal(o);
        else if (type == Number.class) r = o instanceof Number ? o : ValueConverter.toBigDecimal(o);
        else if (type == byte[].class) r = ValueConverter.toBytes(o);
        else if (type == Timestamp.class) r = ValueConverter.toTimestamp(o);
        else if (type == Date.class) r = ValueConverter.toDate(o);
        else if (type == Time.class) r = ValueConverter.toTime(o);
        else if (type == java.time.LocalDateTime.class) r = ValueConverter.toTimestamp(o).toLocalDateTime();
        else if (type == java.time.LocalDate.class) r = ValueConverter.toDate(o).toLocalDate();
        else if (type == java.time.LocalTime.class) r = ValueConverter.toTime(o).toLocalTime();
        else if (type == java.time.Instant.class) r = ValueConverter.toTimestamp(o).toInstant();
        else throw new SQLException("Cannot convert a " + o.getClass().getName() + " to " + type.getName());
        return (T) r;
    }

    // --- Metadata and Lifecycle ---

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkClosed();
        return new OpenCanardResultSetMetaData(currentBatch);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkClosed();
        if (currentBatch == null) throw new SQLException("No result columns available");
        List<Field> fields = currentBatch.getSchema().getFields();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equalsIgnoreCase(columnLabel)) return i + 1;
        }
        throw new SQLException("Column '" + columnLabel + "' not found");
    }

    @Override
    public void close() throws SQLException {
        if (closed) return;
        try {
            if (currentStream != null) currentStream.close();
            closed = true;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    private void checkClosed() throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed");
    }

    // --- Boilerplate Stubs for compatibility ---

    @Override public Statement getStatement() { return statement; }
    @Override public boolean isClosed() { return closed; }
    @Override public int getType() { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getFetchSize() throws SQLException { return statement.getFetchSize(); }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper");
    }

    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }

	@Override
	public String getString(String columnLabel) throws SQLException {
		return getString(findColumn(columnLabel));
	}

	@Override
	public boolean getBoolean(String columnLabel) throws SQLException {
		return getBoolean(findColumn(columnLabel));
	}

	@Override
	public byte getByte(String columnLabel) throws SQLException {
		return getByte(findColumn(columnLabel));
	}

	@Override
	public short getShort(String columnLabel) throws SQLException {
		return getShort(findColumn(columnLabel));
	}

	@Override
	public int getInt(String columnLabel) throws SQLException {
		return getInt(findColumn(columnLabel));
	}

	@Override
	public long getLong(String columnLabel) throws SQLException {
		return getLong(findColumn(columnLabel));
	}

	@Override
	public float getFloat(String columnLabel) throws SQLException {
		return getFloat(findColumn(columnLabel));
	}

	@Override
	public double getDouble(String columnLabel) throws SQLException {
		return getDouble(findColumn(columnLabel));
	}

	@Override
	public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
		return getBigDecimal(findColumn(columnLabel), scale);
	}

	@Override
	public byte[] getBytes(String columnLabel) throws SQLException {
		return getBytes(findColumn(columnLabel));
	}

	@Override
	public Date getDate(String columnLabel) throws SQLException {
		return getDate(findColumn(columnLabel));
	}

	@Override
	public Time getTime(String columnLabel) throws SQLException {
		return getTime(findColumn(columnLabel));
	}

	@Override
	public Timestamp getTimestamp(String columnLabel) throws SQLException {
		return getTimestamp(findColumn(columnLabel));
	}

	@Override
	public InputStream getAsciiStream(String columnLabel) throws SQLException {
		return getAsciiStream(findColumn(columnLabel));
	}

	@Override
	public InputStream getUnicodeStream(String columnLabel) throws SQLException {
		return getUnicodeStream(findColumn(columnLabel));
	}

	@Override
	public InputStream getBinaryStream(String columnLabel) throws SQLException {
		return getBinaryStream(findColumn(columnLabel));
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
	public String getCursorName() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Reader getCharacterStream(String columnLabel) throws SQLException {
		return getCharacterStream(findColumn(columnLabel));
	}

	@Override
	public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
		return getBigDecimal(findColumn(columnLabel));
	}

	@Override
	public boolean isBeforeFirst() throws SQLException {
		return rowNumber == 0 && !afterLast;
	}

	@Override
	public boolean isAfterLast() throws SQLException {
		return afterLast;
	}

	@Override
	public boolean isFirst() throws SQLException {
		return rowNumber == 1 && !afterLast;
	}

	@Override
	public boolean isLast() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void beforeFirst() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void afterLast() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean first() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean last() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int getRow() throws SQLException {
		return afterLast ? 0 : rowNumber;
	}

	@Override
	public boolean absolute(int row) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean relative(int rows) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean previous() throws SQLException {
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
	public boolean rowUpdated() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean rowInserted() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean rowDeleted() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void updateNull(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBoolean(int columnIndex, boolean x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateByte(int columnIndex, byte x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateShort(int columnIndex, short x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateInt(int columnIndex, int x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateLong(int columnIndex, long x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateFloat(int columnIndex, float x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateDouble(int columnIndex, double x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateString(int columnIndex, String x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBytes(int columnIndex, byte[] x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateDate(int columnIndex, Date x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateTime(int columnIndex, Time x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateObject(int columnIndex, Object x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNull(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBoolean(String columnLabel, boolean x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateByte(String columnLabel, byte x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateShort(String columnLabel, short x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateInt(String columnLabel, int x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateLong(String columnLabel, long x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateFloat(String columnLabel, float x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateDouble(String columnLabel, double x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateString(String columnLabel, String x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBytes(String columnLabel, byte[] x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateDate(String columnLabel, Date x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateTime(String columnLabel, Time x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateObject(String columnLabel, Object x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void insertRow() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateRow() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteRow() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void refreshRow() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void cancelRowUpdates() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void moveToInsertRow() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void moveToCurrentRow() throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Ref getRef(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Blob getBlob(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Clob getClob(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Array getArray(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
		return getObject(findColumn(columnLabel));
	}

	@Override
	public Ref getRef(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Blob getBlob(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Clob getClob(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Array getArray(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Date getDate(String columnLabel, Calendar cal) throws SQLException {
		return getDate(findColumn(columnLabel));
	}

	@Override
	public Time getTime(String columnLabel, Calendar cal) throws SQLException {
		return getTime(findColumn(columnLabel));
	}

	@Override
	public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
		return getTimestamp(findColumn(columnLabel));
	}

	@Override
	public URL getURL(String columnLabel) throws SQLException {
		return getURL(findColumn(columnLabel));
	}

	@Override
	public void updateRef(int columnIndex, Ref x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateRef(String columnLabel, Ref x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBlob(int columnIndex, Blob x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBlob(String columnLabel, Blob x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateClob(int columnIndex, Clob x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateClob(String columnLabel, Clob x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateArray(int columnIndex, Array x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateArray(String columnLabel, Array x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public RowId getRowId(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RowId getRowId(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void updateRowId(int columnIndex, RowId x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateRowId(String columnLabel, RowId x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public int getHoldability() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void updateNString(int columnIndex, String nString) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNString(String columnLabel, String nString) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public NClob getNClob(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NClob getNClob(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SQLXML getSQLXML(int columnIndex) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SQLXML getSQLXML(String columnLabel) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String getNString(String columnLabel) throws SQLException {
		return getString(findColumn(columnLabel));
	}

	@Override
	public Reader getNCharacterStream(String columnLabel) throws SQLException {
		return getCharacterStream(findColumn(columnLabel));
	}

	@Override
	public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateClob(int columnIndex, Reader reader) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateClob(String columnLabel, Reader reader) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNClob(int columnIndex, Reader reader) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNClob(String columnLabel, Reader reader) throws SQLException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
		return getObject(findColumn(columnLabel), type);
	}
}