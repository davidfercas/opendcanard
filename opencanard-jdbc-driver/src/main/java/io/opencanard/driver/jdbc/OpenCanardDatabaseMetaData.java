package io.opencanard.driver.jdbc;

import java.sql.*;

public class OpenCanardDatabaseMetaData implements DatabaseMetaData {

    private final Connection conn;

    public OpenCanardDatabaseMetaData(Connection conn) {
        this.conn = conn;
    }

    @Override
    public String getDatabaseProductName() {
        return "OpenCanard";
    }

    @Override
    public String getDatabaseProductVersion() {
        return "0.1";
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
	public boolean allProceduresAreCallable() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean allTablesAreSelectable() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getURL() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getUserName() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean nullsAreSortedHigh() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean nullsAreSortedLow() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean nullsAreSortedAtStart() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean nullsAreSortedAtEnd() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getDriverName() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getDriverVersion() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getDriverMajorVersion() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getDriverMinorVersion() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean usesLocalFiles() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean usesLocalFilePerTable() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMixedCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesUpperCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesLowerCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesMixedCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getIdentifierQuoteString() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSQLKeywords() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getNumericFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getStringFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSystemFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getTimeDateFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSearchStringEscape() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getExtraNameCharacters() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean supportsAlterTableWithAddColumn() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsAlterTableWithDropColumn() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsColumnAliasing() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean nullPlusNonNullIsNull() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsConvert() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsConvert(int fromType, int toType) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsTableCorrelationNames() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsDifferentTableCorrelationNames() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsExpressionsInOrderBy() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOrderByUnrelated() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGroupBy() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGroupByUnrelated() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGroupByBeyondSelect() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsLikeEscapeClause() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMultipleResultSets() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMultipleTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsNonNullableColumns() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMinimumSQLGrammar() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCoreSQLGrammar() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsExtendedSQLGrammar() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsANSI92EntryLevelSQL() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsANSI92IntermediateSQL() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsANSI92FullSQL() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsIntegrityEnhancementFacility() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOuterJoins() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsFullOuterJoins() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsLimitedOuterJoins() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getSchemaTerm() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getProcedureTerm() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getCatalogTerm() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isCatalogAtStart() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getCatalogSeparator() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean supportsSchemasInDataManipulation() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInProcedureCalls() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInTableDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInIndexDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInDataManipulation() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInProcedureCalls() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInTableDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsPositionedDelete() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsPositionedUpdate() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSelectForUpdate() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsStoredProcedures() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInComparisons() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInExists() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInIns() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInQuantifieds() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCorrelatedSubqueries() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsUnion() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsUnionAll() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int getMaxBinaryLiteralLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxCharLiteralLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInGroupBy() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInIndex() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInOrderBy() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInSelect() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInTable() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxConnections() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxCursorNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxIndexLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxSchemaNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxProcedureNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxCatalogNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxRowSize() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int getMaxStatementLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxStatements() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxTableNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxTablesInSelect() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxUserNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getDefaultTransactionIsolation() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean supportsTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
			throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS PROCEDURE_CAT, CAST(NULL AS VARCHAR) AS PROCEDURE_SCHEM, CAST(NULL AS VARCHAR) AS PROCEDURE_NAME, CAST(NULL AS VARCHAR) AS RESERVED1, CAST(NULL AS VARCHAR) AS RESERVED2, CAST(NULL AS VARCHAR) AS RESERVED3, CAST(NULL AS VARCHAR) AS REMARKS, CAST(NULL AS SMALLINT) AS PROCEDURE_TYPE, CAST(NULL AS VARCHAR) AS SPECIFIC_NAME WHERE false");
	}

	@Override
	public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
			String columnNamePattern) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
			throws SQLException {
		StringBuilder sql = new StringBuilder(
				"SELECT * FROM (SELECT table_catalog AS TABLE_CAT, table_schema AS TABLE_SCHEM, table_name AS TABLE_NAME, "
						+ "CASE WHEN table_type = 'BASE TABLE' THEN 'TABLE' WHEN table_type = 'VIEW' THEN 'VIEW' ELSE table_type END AS TABLE_TYPE, "
						+ "CAST(NULL AS VARCHAR) AS REMARKS, CAST(NULL AS VARCHAR) AS TYPE_CAT, CAST(NULL AS VARCHAR) AS TYPE_SCHEM, "
						+ "CAST(NULL AS VARCHAR) AS TYPE_NAME, CAST(NULL AS VARCHAR) AS SELF_REFERENCING_COL_NAME, "
						+ "CAST(NULL AS VARCHAR) AS REF_GENERATION "
						+ "FROM information_schema.tables WHERE table_catalog NOT IN ('system', 'temp')) t WHERE 1 = 1");
		equalsFilter(sql, "TABLE_CAT", catalog);
		likeFilter(sql, "TABLE_SCHEM", schemaPattern);
		likeFilter(sql, "TABLE_NAME", tableNamePattern);
		if (types != null && types.length > 0) {
			sql.append(" AND TABLE_TYPE IN (");
			for (int i = 0; i < types.length; i++) {
				sql.append(i > 0 ? ", " : "").append(literal(types[i]));
			}
			sql.append(")");
		}
		sql.append(" ORDER BY TABLE_TYPE, TABLE_CAT, TABLE_SCHEM, TABLE_NAME");
		return query(sql.toString());
	}

	@Override
	public ResultSet getSchemas() throws SQLException {
		return getSchemas(null, null);
	}

	@Override
	public ResultSet getCatalogs() throws SQLException {
		// DuckDB catalogs are the attached databases
		return query("SELECT DISTINCT catalog_name AS TABLE_CAT FROM information_schema.schemata "
				+ "WHERE catalog_name NOT IN ('system', 'temp') ORDER BY TABLE_CAT");
	}

	@Override
	public ResultSet getTableTypes() throws SQLException {
		return query("SELECT 'TABLE' AS TABLE_TYPE UNION ALL SELECT 'VIEW'");
	}

	@Override
	public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
			throws SQLException {
		StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT table_catalog AS TABLE_CAT, table_schema AS TABLE_SCHEM, "
				+ "table_name AS TABLE_NAME, column_name AS COLUMN_NAME, CAST(CASE WHEN upper(data_type) LIKE 'DECIMAL%' THEN 3 WHEN upper(data_type) LIKE 'TIMESTAMP%WITH TIME ZONE%' THEN 2014 WHEN upper(data_type) LIKE 'TIMESTAMP%' THEN 93 WHEN upper(data_type) LIKE 'TIME%' THEN 92 WHEN upper(data_type) = 'VARCHAR' THEN 12 WHEN upper(data_type) = 'INTEGER' THEN 4 WHEN upper(data_type) = 'BIGINT' THEN -5 WHEN upper(data_type) = 'SMALLINT' THEN 5 WHEN upper(data_type) = 'TINYINT' THEN -6 WHEN upper(data_type) = 'BOOLEAN' THEN 16 WHEN upper(data_type) = 'DOUBLE' THEN 8 WHEN upper(data_type) IN ('FLOAT', 'REAL') THEN 7 WHEN upper(data_type) = 'DATE' THEN 91 WHEN upper(data_type) = 'BLOB' THEN -2 WHEN upper(data_type) IN ('HUGEINT', 'UBIGINT', 'UHUGEINT') THEN 2 WHEN upper(data_type) IN ('UINTEGER') THEN -5 WHEN upper(data_type) IN ('USMALLINT', 'UTINYINT') THEN 4 ELSE 1111 END AS INTEGER) AS DATA_TYPE, "
				+ "data_type AS TYPE_NAME, CAST(coalesce(character_maximum_length, numeric_precision, datetime_precision) AS INTEGER) AS COLUMN_SIZE, "
				+ "CAST(NULL AS INTEGER) AS BUFFER_LENGTH, CAST(numeric_scale AS INTEGER) AS DECIMAL_DIGITS, "
				+ "CAST(numeric_precision_radix AS INTEGER) AS NUM_PREC_RADIX, "
				+ "CAST(CASE WHEN is_nullable = 'YES' THEN 1 ELSE 0 END AS INTEGER) AS NULLABLE, "
				+ "CAST(NULL AS VARCHAR) AS REMARKS, column_default AS COLUMN_DEF, CAST(NULL AS INTEGER) AS SQL_DATA_TYPE, "
				+ "CAST(NULL AS INTEGER) AS SQL_DATETIME_SUB, CAST(character_maximum_length AS INTEGER) AS CHAR_OCTET_LENGTH, "
				+ "CAST(ordinal_position AS INTEGER) AS ORDINAL_POSITION, is_nullable AS IS_NULLABLE, "
				+ "CAST(NULL AS VARCHAR) AS SCOPE_CATALOG, CAST(NULL AS VARCHAR) AS SCOPE_SCHEMA, CAST(NULL AS VARCHAR) AS SCOPE_TABLE, "
				+ "CAST(NULL AS SMALLINT) AS SOURCE_DATA_TYPE, 'NO' AS IS_AUTOINCREMENT, 'NO' AS IS_GENERATEDCOLUMN "
				+ "FROM information_schema.columns WHERE table_catalog NOT IN ('system', 'temp')) t WHERE 1 = 1");
		equalsFilter(sql, "TABLE_CAT", catalog);
		likeFilter(sql, "TABLE_SCHEM", schemaPattern);
		likeFilter(sql, "TABLE_NAME", tableNamePattern);
		likeFilter(sql, "COLUMN_NAME", columnNamePattern);
		sql.append(" ORDER BY TABLE_CAT, TABLE_SCHEM, TABLE_NAME, ORDINAL_POSITION");
		return query(sql.toString());
	}

	@Override
	public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
			throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS TABLE_CAT, CAST(NULL AS VARCHAR) AS TABLE_SCHEM, CAST(NULL AS VARCHAR) AS TABLE_NAME, CAST(NULL AS VARCHAR) AS COLUMN_NAME, CAST(NULL AS VARCHAR) AS GRANTOR, CAST(NULL AS VARCHAR) AS GRANTEE, CAST(NULL AS VARCHAR) AS PRIVILEGE, CAST(NULL AS VARCHAR) AS IS_GRANTABLE WHERE false");
	}

	@Override
	public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
			throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS TABLE_CAT, CAST(NULL AS VARCHAR) AS TABLE_SCHEM, CAST(NULL AS VARCHAR) AS TABLE_NAME, CAST(NULL AS VARCHAR) AS GRANTOR, CAST(NULL AS VARCHAR) AS GRANTEE, CAST(NULL AS VARCHAR) AS PRIVILEGE, CAST(NULL AS VARCHAR) AS IS_GRANTABLE WHERE false");
	}

	@Override
	public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
			throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS TABLE_CAT, CAST(NULL AS VARCHAR) AS TABLE_SCHEM, CAST(NULL AS VARCHAR) AS TABLE_NAME, CAST(NULL AS VARCHAR) AS COLUMN_NAME, CAST(NULL AS SMALLINT) AS KEY_SEQ, CAST(NULL AS VARCHAR) AS PK_NAME WHERE false");
	}

	@Override
	public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS PKTABLE_CAT, CAST(NULL AS VARCHAR) AS PKTABLE_SCHEM, CAST(NULL AS VARCHAR) AS PKTABLE_NAME, CAST(NULL AS VARCHAR) AS PKCOLUMN_NAME, CAST(NULL AS VARCHAR) AS FKTABLE_CAT, CAST(NULL AS VARCHAR) AS FKTABLE_SCHEM, CAST(NULL AS VARCHAR) AS FKTABLE_NAME, CAST(NULL AS VARCHAR) AS FKCOLUMN_NAME, CAST(NULL AS SMALLINT) AS KEY_SEQ, CAST(NULL AS SMALLINT) AS UPDATE_RULE, CAST(NULL AS SMALLINT) AS DELETE_RULE, CAST(NULL AS VARCHAR) AS FK_NAME, CAST(NULL AS VARCHAR) AS PK_NAME, CAST(NULL AS SMALLINT) AS DEFERRABILITY WHERE false");
	}

	@Override
	public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS PKTABLE_CAT, CAST(NULL AS VARCHAR) AS PKTABLE_SCHEM, CAST(NULL AS VARCHAR) AS PKTABLE_NAME, CAST(NULL AS VARCHAR) AS PKCOLUMN_NAME, CAST(NULL AS VARCHAR) AS FKTABLE_CAT, CAST(NULL AS VARCHAR) AS FKTABLE_SCHEM, CAST(NULL AS VARCHAR) AS FKTABLE_NAME, CAST(NULL AS VARCHAR) AS FKCOLUMN_NAME, CAST(NULL AS SMALLINT) AS KEY_SEQ, CAST(NULL AS SMALLINT) AS UPDATE_RULE, CAST(NULL AS SMALLINT) AS DELETE_RULE, CAST(NULL AS VARCHAR) AS FK_NAME, CAST(NULL AS VARCHAR) AS PK_NAME, CAST(NULL AS SMALLINT) AS DEFERRABILITY WHERE false");
	}

	@Override
	public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
			String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS PKTABLE_CAT, CAST(NULL AS VARCHAR) AS PKTABLE_SCHEM, CAST(NULL AS VARCHAR) AS PKTABLE_NAME, CAST(NULL AS VARCHAR) AS PKCOLUMN_NAME, CAST(NULL AS VARCHAR) AS FKTABLE_CAT, CAST(NULL AS VARCHAR) AS FKTABLE_SCHEM, CAST(NULL AS VARCHAR) AS FKTABLE_NAME, CAST(NULL AS VARCHAR) AS FKCOLUMN_NAME, CAST(NULL AS SMALLINT) AS KEY_SEQ, CAST(NULL AS SMALLINT) AS UPDATE_RULE, CAST(NULL AS SMALLINT) AS DELETE_RULE, CAST(NULL AS VARCHAR) AS FK_NAME, CAST(NULL AS VARCHAR) AS PK_NAME, CAST(NULL AS SMALLINT) AS DEFERRABILITY WHERE false");
	}

	@Override
	public ResultSet getTypeInfo() throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS TYPE_NAME, CAST(NULL AS INTEGER) AS DATA_TYPE, CAST(NULL AS INTEGER) AS PRECISION, CAST(NULL AS VARCHAR) AS LITERAL_PREFIX, CAST(NULL AS VARCHAR) AS LITERAL_SUFFIX, CAST(NULL AS VARCHAR) AS CREATE_PARAMS, CAST(NULL AS SMALLINT) AS NULLABLE, CAST(NULL AS SMALLINT) AS CASE_SENSITIVE, CAST(NULL AS SMALLINT) AS SEARCHABLE, CAST(NULL AS SMALLINT) AS UNSIGNED_ATTRIBUTE, CAST(NULL AS SMALLINT) AS FIXED_PREC_SCALE, CAST(NULL AS SMALLINT) AS AUTO_INCREMENT, CAST(NULL AS VARCHAR) AS LOCAL_TYPE_NAME, CAST(NULL AS SMALLINT) AS MINIMUM_SCALE, CAST(NULL AS SMALLINT) AS MAXIMUM_SCALE, CAST(NULL AS INTEGER) AS SQL_DATA_TYPE, CAST(NULL AS INTEGER) AS SQL_DATETIME_SUB, CAST(NULL AS INTEGER) AS NUM_PREC_RADIX WHERE false");
	}

	@Override
	public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
			throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS TABLE_CAT, CAST(NULL AS VARCHAR) AS TABLE_SCHEM, CAST(NULL AS VARCHAR) AS TABLE_NAME, CAST(NULL AS SMALLINT) AS NON_UNIQUE, CAST(NULL AS VARCHAR) AS INDEX_QUALIFIER, CAST(NULL AS VARCHAR) AS INDEX_NAME, CAST(NULL AS SMALLINT) AS TYPE, CAST(NULL AS SMALLINT) AS ORDINAL_POSITION, CAST(NULL AS VARCHAR) AS COLUMN_NAME, CAST(NULL AS VARCHAR) AS ASC_OR_DESC, CAST(NULL AS INTEGER) AS CARDINALITY, CAST(NULL AS INTEGER) AS PAGES, CAST(NULL AS VARCHAR) AS FILTER_CONDITION WHERE false");
	}

	@Override
	public boolean supportsResultSetType(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean ownUpdatesAreVisible(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean ownDeletesAreVisible(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean ownInsertsAreVisible(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean othersUpdatesAreVisible(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean othersDeletesAreVisible(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean othersInsertsAreVisible(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean updatesAreDetected(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean deletesAreDetected(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean insertsAreDetected(int type) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsBatchUpdates() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
			throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Connection getConnection() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean supportsSavepoints() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsNamedParameters() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMultipleOpenResults() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGetGeneratedKeys() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
			String attributeNamePattern) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean supportsResultSetHoldability(int holdability) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getDatabaseMajorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getDatabaseMinorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getJDBCMajorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getJDBCMinorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getSQLStateType() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean locatorsUpdateCopy() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsStatementPooling() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public RowIdLifetime getRowIdLifetime() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
		StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT schema_name AS TABLE_SCHEM, catalog_name AS TABLE_CATALOG FROM information_schema.schemata WHERE catalog_name NOT IN ('system', 'temp')) t WHERE 1 = 1");
		equalsFilter(sql, "TABLE_CATALOG", catalog);
		likeFilter(sql, "TABLE_SCHEM", schemaPattern);
		sql.append(" ORDER BY TABLE_CATALOG, TABLE_SCHEM");
		return query(sql.toString());
	}

	@Override
	public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public ResultSet getClientInfoProperties() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
			throws SQLException {
		return query("SELECT CAST(NULL AS VARCHAR) AS FUNCTION_CAT, CAST(NULL AS VARCHAR) AS FUNCTION_SCHEM, CAST(NULL AS VARCHAR) AS FUNCTION_NAME, CAST(NULL AS VARCHAR) AS REMARKS, CAST(NULL AS SMALLINT) AS FUNCTION_TYPE, CAST(NULL AS VARCHAR) AS SPECIFIC_NAME WHERE false");
	}

	@Override
	public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
			String columnNamePattern) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
			String columnNamePattern) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean generatedKeyAlwaysReturned() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

    // Stub the rest

	// ---- helpers for the metadata queries ----

	private ResultSet query(String sql) throws SQLException {
		return this.conn.createStatement().executeQuery(sql);
	}

	/** SQL string literal: quotes are doubled. */
	private static String literal(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	/** Adds "AND column = 'value'" unless value is null or empty. */
	private static void equalsFilter(StringBuilder sql, String column, String value) {
		if (value != null && !value.isEmpty()) {
			sql.append(" AND ").append(column).append(" = ").append(literal(value));
		}
	}

	/** Adds "AND column LIKE 'pattern'" (JDBC pattern: % and _, backslash escapes) unless null or "%". */
	private static void likeFilter(StringBuilder sql, String column, String pattern) {
		if (pattern != null && !pattern.equals("%")) {
			sql.append(" AND ").append(column).append(" LIKE ").append(literal(pattern)).append(" ESCAPE '\\'");
		}
	}
}
