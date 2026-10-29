package io.opencanard.driver.jdbc;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.*;

import java.sql.*;
import java.util.List;

public class OpenCanardResultSetMetaData implements ResultSetMetaData {

    private final List<Field> fields;

    public OpenCanardResultSetMetaData(VectorSchemaRoot root) {
        // Handle cases where root might be null before first batch
        this.fields = root != null ? root.getSchema().getFields() : java.util.Collections.emptyList();
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
            if (bits <= 8) return Types.TINYINT;
            if (bits <= 16) return Types.SMALLINT;
            return bits <= 32 ? Types.INTEGER : Types.BIGINT;
        }
        if (type instanceof ArrowType.FloatingPoint) {
            ArrowType.FloatingPoint fp = (ArrowType.FloatingPoint) type;
            return fp.getPrecision() == org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE ? Types.REAL : Types.DOUBLE;
        }
        if (type instanceof ArrowType.Bool) return Types.BOOLEAN;
        if (type instanceof ArrowType.Utf8 || type instanceof ArrowType.LargeUtf8) return Types.VARCHAR;
        if (type instanceof ArrowType.Binary || type instanceof ArrowType.LargeBinary || type instanceof ArrowType.FixedSizeBinary) return Types.VARBINARY;
        if (type instanceof ArrowType.Decimal) return Types.DECIMAL;
        if (type instanceof ArrowType.Date) return Types.DATE;
        if (type instanceof ArrowType.Timestamp) return Types.TIMESTAMP;
        if (type instanceof ArrowType.Time) return Types.TIME;

        return Types.OTHER;
    }

    @Override
    public int getPrecision(int column) {
        ArrowType type = fields.get(column - 1).getType();
        if (type instanceof ArrowType.Decimal) {
            return ((ArrowType.Decimal) type).getPrecision();
        }
        return 0;
    }

    @Override
    public int getScale(int column) {
        ArrowType type = fields.get(column - 1).getType();
        if (type instanceof ArrowType.Decimal) {
            return ((ArrowType.Decimal) type).getScale();
        }
        return 0;
    }

    @Override
    public boolean isSigned(int column) {
        ArrowType type = fields.get(column - 1).getType();
        if (type instanceof ArrowType.Int) {
            return ((ArrowType.Int) type).getIsSigned();
        }
        return true; 
    }

    @Override
    public int isNullable(int column) {
        return fields.get(column - 1).isNullable() ? columnNullable : columnNoNulls;
    }

    // --- Standard Wrapper Logic ---

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("MetaData is not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    // --- Boilerplate (Satisfying standard JDBC requirements) ---

    @Override public String getColumnTypeName(int column) {
        ArrowType type = fields.get(column - 1).getType();
        switch (getColumnType(column)) {
        case Types.TINYINT: return "TINYINT";
        case Types.SMALLINT: return "SMALLINT";
        case Types.INTEGER: return "INTEGER";
        case Types.BIGINT: return "BIGINT";
        case Types.REAL: return "REAL";
        case Types.DOUBLE: return "DOUBLE";
        case Types.BOOLEAN: return "BOOLEAN";
        case Types.VARCHAR: return "VARCHAR";
        case Types.VARBINARY: return "BLOB";
        case Types.DECIMAL: return "DECIMAL";
        case Types.DATE: return "DATE";
        case Types.TIME: return "TIME";
        case Types.TIMESTAMP:
            return type instanceof ArrowType.Timestamp && ((ArrowType.Timestamp) type).getTimezone() != null
                    ? "TIMESTAMP WITH TIME ZONE" : "TIMESTAMP";
        default: return type.toString();
        }
    }
    @Override public String getTableName(int column) { return ""; }
    @Override public String getSchemaName(int column) { return ""; }
    @Override public boolean isAutoIncrement(int column) { return false; }
    @Override public boolean isCaseSensitive(int column) { return true; }
    @Override public boolean isSearchable(int column) { return true; }
    @Override public boolean isCurrency(int column) { return false; }
    @Override public int getColumnDisplaySize(int column) { return 20; }
    @Override public String getCatalogName(int column) { return ""; }
    @Override public boolean isReadOnly(int column) { return true; }
    @Override public boolean isWritable(int column) { return false; }
    @Override public boolean isDefinitelyWritable(int column) { return false; }
    @Override public String getColumnClassName(int column) {
        switch (getColumnType(column)) {
        case Types.TINYINT: return Byte.class.getName();
        case Types.SMALLINT: return Short.class.getName();
        case Types.INTEGER: return Integer.class.getName();
        case Types.BIGINT: return Long.class.getName();
        case Types.REAL: return Float.class.getName();
        case Types.DOUBLE: return Double.class.getName();
        case Types.BOOLEAN: return Boolean.class.getName();
        case Types.VARCHAR: return String.class.getName();
        case Types.VARBINARY: return byte[].class.getName();
        case Types.DECIMAL: return java.math.BigDecimal.class.getName();
        case Types.DATE: return java.sql.Date.class.getName();
        case Types.TIME: return java.sql.Time.class.getName();
        case Types.TIMESTAMP: return java.sql.Timestamp.class.getName();
        default: return Object.class.getName();
        }
    }
}