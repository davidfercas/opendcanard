/* Pure helpers of the console (no DOM), so they can be tested with node. */
(function (root) {
	'use strict';

	var KEYWORDS = ('select from where group by order having limit offset distinct as and or not in is null like ilike ' +
		'between exists case when then else end join left right full inner outer cross on using union all intersect except ' +
		'insert into values update set delete create table view index unique primary key foreign references default ' +
		'drop alter add column schema if replace temp temporary with recursive over partition window asc desc nulls first last ' +
		'copy to attach detach use pragma describe show summarize explain analyze begin commit rollback true false cast ' +
		'interval returning macro sequence type').split(' ');
	var KEYWORD_SET = {};
	KEYWORDS.forEach(function (k) { KEYWORD_SET[k] = true; });

	function escapeHtml(text) {
		return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
	}

	/** Quotes an identifier only when it needs it. */
	function quoteIdent(name) {
		return /^[a-z_][a-z0-9_]*$/.test(name) && !KEYWORD_SET[name] ? name : '"' + String(name).replace(/"/g, '""') + '"';
	}

	/** SELECT used when a table is opened from the tree. The database name is added only when the connection has several. */
	function selectAll(schema, table, limit, database) {
		return 'SELECT * FROM ' + (database ? quoteIdent(database) + '.' : '') + quoteIdent(schema) + '.' + quoteIdent(table) + ' LIMIT ' + (limit || 100) + ';';
	}

	var TOKEN = new RegExp([
		'(--[^\\n]*)',                                   // 1 line comment
		'(\\/\\*[\\s\\S]*?(?:\\*\\/|$))',                // 2 block comment
		"('(?:[^']|'')*(?:'|$))",                        // 3 string
		'("(?:[^"]|"")*(?:"|$))',                        // 4 quoted identifier
		'(\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)',    // 5 number
		'([A-Za-z_][A-Za-z0-9_]*)'                       // 6 word
	].join('|'), 'g');

	/** HTML for the colored copy of the editor text. Every character of the input is kept, in order. */
	function highlight(sql) {
		var out = '';
		var last = 0;
		var m;
		TOKEN.lastIndex = 0;
		while ((m = TOKEN.exec(sql)) !== null) {
			out += escapeHtml(sql.slice(last, m.index));
			var text = escapeHtml(m[0]);
			if (m[1] || m[2]) out += '<span class="t-comment">' + text + '</span>';
			else if (m[3]) out += '<span class="t-string">' + text + '</span>';
			else if (m[4]) out += '<span class="t-ident">' + text + '</span>';
			else if (m[5]) out += '<span class="t-number">' + text + '</span>';
			else if (KEYWORD_SET[m[6].toLowerCase()]) out += '<span class="t-keyword">' + text + '</span>';
			else out += text;
			last = m.index + m[0].length;
			if (m[0].length === 0) TOKEN.lastIndex++;
		}
		return out + escapeHtml(sql.slice(last));
	}

	/** Text shown in a cell, and whether it is a SQL NULL. Nested values are shown as JSON. */
	function cellText(value) {
		if (value === null || value === undefined) return { text: 'NULL', isNull: true };
		if (typeof value === 'object') return { text: JSON.stringify(value), isNull: false };
		return { text: String(value), isNull: false };
	}

	function isNumeric(typeName) {
		return /^(TINYINT|SMALLINT|INTEGER|BIGINT|HUGEINT|UTINYINT|USMALLINT|UINTEGER|UBIGINT|UHUGEINT|FLOAT|DOUBLE|REAL|DECIMAL|NUMERIC|INT\d*)\b/i.test(typeName || '');
	}

	function csvField(text) {
		return /[",\r\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
	}

	/** CSV (RFC 4180) of a result; NULL is an empty field. */
	function toCsv(columns, rows) {
		var lines = [columns.map(function (c) { return csvField(c.name); }).join(',')];
		rows.forEach(function (row) {
			lines.push(row.map(function (v) {
				var cell = cellText(v);
				return cell.isNull ? '' : csvField(cell.text);
			}).join(','));
		});
		return lines.join('\r\n') + '\r\n';
	}

	/** A SELECT that reads a file of a bucket, or null for a type DuckDB cannot read directly. */
	function sampleQuery(url, limit) {
		var plain = String(url).replace(/\.(gz|zst)$/i, '');
		var m = /\.([A-Za-z0-9]+)$/.exec(plain);
		var fn = { parquet: 'read_parquet', csv: 'read_csv_auto', tsv: 'read_csv_auto', json: 'read_json_auto',
			jsonl: 'read_json_auto', ndjson: 'read_json_auto' }[m ? m[1].toLowerCase() : ''];
		if (!fn) return null;
		return 'SELECT * FROM ' + fn + "('" + String(url).replace(/'/g, "''") + "') LIMIT " + (limit || 100) + ';';
	}

	function formatBytes(n) {
		if (n < 1024) return n + ' B';
		var units = ['KB', 'MB', 'GB', 'TB'];
		var v = n / 1024;
		var i = 0;
		while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
		return v.toFixed(v >= 100 ? 0 : 1) + ' ' + units[i];
	}

	var api = { sampleQuery: sampleQuery, formatBytes: formatBytes, escapeHtml: escapeHtml, quoteIdent: quoteIdent, selectAll: selectAll, highlight: highlight,
		cellText: cellText, isNumeric: isNumeric, toCsv: toCsv };
	if (typeof module !== 'undefined' && module.exports) module.exports = api;
	else root.SqlUtil = api;
})(this);
