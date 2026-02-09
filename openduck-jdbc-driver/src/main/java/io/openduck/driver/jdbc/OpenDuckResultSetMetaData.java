package io.openduck.driver.jdbc;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.*;

import java.sql.*;
import java.util.List;

public class OpenDuckResultSetMetaData implements ResultSetMetaData {

    private final List<Field> fields;

    public OpenDuckResultSetMetaData(VectorSchemaRoot root) {
        this.fields = root.getSchema().getFields();
    }

    @Override
    public int getColumnCount() {
        return fields.size();
    }

    @Override
    public String getColumnName(int column) {
        return fields.get(column - 1).getName();
    }

    @Override
    public String getColumnLabel(int column) {
        return getColumnName(column);
    }

    @Override
    public int getColumnType(int column) {
        ArrowType type = fields.get(column - 1).getType();

        if (type instanceof ArrowType.Int) {
            int bits = ((ArrowType.Int) type).getBitWidth();
            if (bits <= 32) return Types.INTEGER;
            return Types.BIGINT;
        }

        if (type instanceof ArrowType.FloatingPoint)
            return Types.DOUBLE;

        if (type instanceof ArrowType.Bool)
            return Types.BOOLEAN;

        if (type instanceof ArrowType.Utf8)
            return Types.VARCHAR;

        if (type instanceof ArrowType.Timestamp)
            return Types.TIMESTAMP;

        return Types.OTHER;
    }

    @Override
    public String getColumnTypeName(int column) {
        return fields.get(column - 1).getType().toString();
    }

    @Override
    public int isNullable(int column) throws SQLException {
        Field field = fields.get(column - 1);

        if (field.isNullable()) {
            return ResultSetMetaData.columnNullable;
        } else {
            return ResultSetMetaData.columnNoNulls;
        }
    }

    // ---- Minimal JDBC compliance ----

    @Override public String getTableName(int column) { return ""; }
    @Override public String getSchemaName(int column) { return ""; }
    @Override public int getPrecision(int column) { return 0; }
    @Override public int getScale(int column) { return 0; }
    @Override public boolean isAutoIncrement(int column) { return false; }
    @Override public boolean isCaseSensitive(int column) { return true; }
    @Override public boolean isSearchable(int column) { return true; }
    @Override public boolean isCurrency(int column) { return false; }
    @Override public boolean isSigned(int column) { return true; }
    @Override public int getColumnDisplaySize(int column) { return 20; }
    @Override public String getCatalogName(int column) { return ""; }
    @Override public boolean isReadOnly(int column) { return true; }
    @Override public boolean isWritable(int column) { return false; }
    @Override public boolean isDefinitelyWritable(int column) { return false; }
    @Override public String getColumnClassName(int column) { return Object.class.getName(); }

    @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
