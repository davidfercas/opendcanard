/* OpenCanard web console: structure of the connection on the left, SQL console on the right. */
(function () {
	'use strict';

	var U = window.SqlUtil;
	var $ = function (id) { return document.getElementById(id); };

	var state = {
		token: null,
		session: null,
		catalog: null,
		columns: {},            // table key -> column list, loaded when a table is opened in the tree
		expanded: new Set(),
		selected: null,
		result: null,
		running: false,
		renderId: 0,
		storages: [],
		storageWarning: null
	};

	// ---------- small helpers ----------

	function store(key, value) {
		try {
			if (value === undefined) return window.localStorage.getItem(key);
			if (value === null) window.localStorage.removeItem(key); else window.localStorage.setItem(key, value);
		} catch (e) { /* storage can be blocked */ }
		return null;
	}

	function tokenStore(value) {
		try {
			if (value === undefined) return window.sessionStorage.getItem('oc.token');
			if (value === null) window.sessionStorage.removeItem('oc.token'); else window.sessionStorage.setItem('oc.token', value);
		} catch (e) { /* storage can be blocked */ }
		return null;
	}

	function el(tag, attrs, children) {
		var node = document.createElement(tag);
		Object.keys(attrs || {}).forEach(function (k) {
			if (k === 'class') node.className = attrs[k];
			else if (k === 'text') node.textContent = attrs[k];
			else node.setAttribute(k, attrs[k]);
		});
		(children || []).forEach(function (c) { if (c) node.appendChild(c); });
		return node;
	}

	function icon(id, cls) {
		var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
		svg.setAttribute('class', 'ico ' + (cls || ''));
		var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
		use.setAttribute('href', '#' + id);
		svg.appendChild(use);
		return svg;
	}

	function plural(n, word) { return n.toLocaleString('en-US') + ' ' + word + (n === 1 ? '' : 's'); }

	// ---------- server calls ----------

	function api(path, options) {
		options = options || {};
		var headers = { 'Accept': 'application/json' };
		if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
		if (options.body !== undefined) headers['Content-Type'] = 'application/json';
		return fetch(path, {
			method: options.method || 'GET',
			headers: headers,
			body: options.body !== undefined ? JSON.stringify(options.body) : undefined
		}).then(function (res) {
			return res.text().then(function (text) {
				var data = null;
				try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
				if (res.status === 401 && state.token) {
					showLogin('Your session ended. Sign in again.');
					throw new Error('Your session ended. Sign in again.');
				}
				if (!res.ok) {
					var message = (data && (data.error || data.title)) || text || res.statusText;
					throw new Error(message);
				}
				return data;
			});
		}, function () {
			throw new Error('Cannot reach the server. Check that OpenCanard is running.');
		});
	}

	// ---------- sign in ----------

	function showLogin(message) {
		state.token = null;
		state.storages = [];
		tokenStore(null);
		$('app').hidden = true;
		$('login').hidden = false;
		var err = $('loginError');
		err.hidden = !message;
		err.textContent = message || '';
		$('pass').value = '';
		$('user').focus();
	}

	$('loginForm').addEventListener('submit', function (event) {
		event.preventDefault();
		var err = $('loginError');
		err.hidden = true;
		var body = { username: $('user').value.trim(), password: $('pass').value };
		fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
			.then(function (res) {
				if (res.status === 401) throw new Error('Wrong user name or password.');
				if (!res.ok) throw new Error('Sign in failed (' + res.status + ').');
				return res.json();
			})
			.then(function (data) {
				state.token = data.token;
				tokenStore(data.token);
				return startApp();
			})
			.catch(function (e) {
				err.textContent = e instanceof TypeError ? 'Cannot reach the server. Check that OpenCanard is running.' : e.message;
				err.hidden = false;
			});
	});

	$('logoutBtn').addEventListener('click', function () { showLogin(); });

	function startApp() {
		return api('/api/console/session').then(function (session) {
			state.session = session;
			$('login').hidden = true;
			$('app').hidden = false;
			$('who').textContent = session.username + (session.admin ? ' (admin)' : '');
			$('connLabel').textContent = session.admin ? 'Metadata database' : 'Personal database';
			state.expanded = new Set();
			state.columns = {};
			state.catalog = null;
			$('sql').focus();
			loadStorages();
			return loadCatalog(true);
		});
	}

	// ---------- structure tree ----------

	var firstLoad = true;

	function loadCatalog(initial) {
		var btn = $('refreshBtn');
		btn.classList.add('busy');
		return api('/api/console/catalog').then(function (catalog) {
			state.catalog = catalog;
			if (initial || firstLoad) expandDefaults(catalog);
			firstLoad = false;
			renderTree();
		}).catch(function (e) {
			state.catalog = null;
			var tree = $('tree');
			tree.textContent = '';
			tree.appendChild(el('div', { class: 'tree-empty', text: e.message }));
		}).then(function () { btn.classList.remove('busy'); });
	}

	function expandDefaults(catalog) {
		catalog.databases.forEach(function (db) {
			state.expanded.add(key('db', db.name));
			db.schemas.forEach(function (schema) {
				if (schema.system) return;
				state.expanded.add(key('schema', db.name, schema.name));
				state.expanded.add(key('tables', db.name, schema.name));
				state.expanded.add(key('views', db.name, schema.name));
			});
		});
	}

	function key() { return JSON.stringify(Array.prototype.slice.call(arguments)); }

	function renderTree() {
		var tree = $('tree');
		var filter = $('filter').value.trim().toLowerCase();
		var filtering = filter.length > 0;
		var scroll = tree.scrollTop;
		tree.textContent = '';

		if (!state.catalog || state.catalog.databases.length === 0) {
			tree.appendChild(el('div', { class: 'tree-empty', text: 'This connection has no databases.' }));
			return;
		}

		var multiDb = state.catalog.databases.length > 1;
		var root = el('ul', { role: 'group' });
		var shown = 0;

		function matches(name) { return !filtering || name.toLowerCase().indexOf(filter) >= 0; }
		function isOpen(k) { return filtering || state.expanded.has(k); }

		state.catalog.databases.forEach(function (db) {
			var dbItems = [];
			db.schemas.forEach(function (schema) {
				var tables = schema.tables.filter(function (t) { return t.type === 'TABLE' && matches(t.name); });
				var views = schema.tables.filter(function (t) { return t.type === 'VIEW' && matches(t.name); });
				var indexes = schema.indexes.filter(function (i) { return matches(i.name); });
				if (filtering && tables.length + views.length + indexes.length === 0) return;

				var schemaKey = key('schema', db.name, schema.name);
				var groups = [];
				if (tables.length) groups.push(group('Tables', 'tables', tables.length, tables.map(function (t) { return tableItem(db.name, schema.name, t, 'i-table', multiDb); })));
				if (views.length) groups.push(group('Views', 'views', views.length, views.map(function (t) { return tableItem(db.name, schema.name, t, 'i-view', multiDb); })));
				if (indexes.length) groups.push(group('Indexes', 'indexes', indexes.length, indexes.map(function (i) { return indexItem(i); })));
				shown += tables.length + views.length + indexes.length;

				function group(label, kind, count, items) {
					var gk = key(kind, db.name, schema.name);
					return branch(node({ depth: 2, key: gk, expandable: true, open: isOpen(gk), icon: null, name: label, sub: String(count), cls: 'group' }), items, isOpen(gk));
				}

				dbItems.push(branch(node({ depth: 1, key: schemaKey, expandable: true, open: isOpen(schemaKey), icon: 'i-schema',
					name: schema.name, cls: schema.system ? 'is-system' : '' }), groups, isOpen(schemaKey)));
			});
			if (filtering && dbItems.length === 0) return;
			var dbKey = key('db', db.name);
			root.appendChild(branch(node({ depth: 0, key: dbKey, expandable: true, open: isOpen(dbKey), icon: 'i-db', name: db.name }), dbItems, isOpen(dbKey)));
		});

		if (filtering && shown === 0) {
			tree.appendChild(el('div', { class: 'tree-empty', text: 'Nothing matches "' + $('filter').value.trim() + '".' }));
			return;
		}
		tree.appendChild(root);
		tree.scrollTop = scroll;
	}

	/** li with the row of the node and, when open, its children */
	function branch(row, children, open) {
		var li = el('li', { role: 'none' }, [row]);
		if (open && children.length) {
			var ul = el('ul', { role: 'group' });
			children.forEach(function (c) { ul.appendChild(c); });
			li.appendChild(ul);
		}
		return li;
	}

	function node(o) {
		var btn = el('button', { type: 'button', class: 'node ' + (o.cls || ''), role: 'treeitem', 'data-key': o.key });
		btn.style.setProperty('--depth', o.depth);
		if (o.expandable) btn.setAttribute('aria-expanded', String(!!o.open));
		btn.setAttribute('aria-selected', String(state.selected === o.key));
		var chev = icon('i-chevron', 'chev' + (o.expandable ? '' : ' none'));
		btn.appendChild(chev);
		if (o.icon) { var k = icon(o.icon, 'kind'); btn.appendChild(k); }
		btn.appendChild(el('span', { class: 'name', text: o.name }));
		if (o.sub) btn.appendChild(el('span', { class: 'sub', text: o.sub }));
		if (o.title) btn.title = o.title;
		btn.addEventListener('click', function () { onNodeClick(o); });
		if (o.onOpen) btn.addEventListener('dblclick', o.onOpen);
		if (!o.onOpen) return btn;
		var open = el('button', { type: 'button', class: 'open-btn', title: 'Select rows from ' + o.name, 'aria-label': 'Select rows from ' + o.name }, [icon('i-play')]);
		open.addEventListener('click', o.onOpen);
		return el('div', { class: 'row' }, [btn, open]);
	}

	function tableItem(db, schema, table, iconId, multiDb) {
		var tk = key('table', db, schema, table.name);
		var open = state.expanded.has(tk);
		var cols = state.columns[tk];
		var children = [];
		if (open && cols) {
			children = cols.map(function (c) {
				var b = el('button', { type: 'button', class: 'node column', role: 'treeitem', title: c.name + ' ' + c.type + (c.nullable ? '' : ' NOT NULL') + (c.default ? ' DEFAULT ' + c.default : '') });
				b.style.setProperty('--depth', 3);
				b.appendChild(icon('i-chevron', 'chev none'));
				b.appendChild(icon(c.primaryKey ? 'i-key' : 'i-column', c.primaryKey ? 'pk' : 'kind'));
				b.appendChild(el('span', { class: 'name', text: c.name }));
				b.appendChild(el('span', { class: 'sub', text: c.type }));
				return el('li', { role: 'none' }, [b]);
			});
		} else if (open) {
			children = [el('li', { role: 'none' }, [el('div', { class: 'tree-empty', text: 'Loading columns' })])];
		}
		var row = node({ depth: 3, key: tk, expandable: true, open: open, icon: iconId, name: table.name,
			cls: 'leaf-type', table: { db: db, schema: schema, name: table.name },
			onOpen: function (event) { event.stopPropagation(); openTable(db, schema, table.name, multiDb); } });
		var li = el('li', { role: 'none' }, [row]);
		if (children.length) {
			var ul = el('ul', { role: 'group' });
			children.forEach(function (c) { ul.appendChild(c); });
			li.appendChild(ul);
		}
		return li;
	}

	function indexItem(index) {
		var b = node({ depth: 3, key: key('index', index.name), expandable: false, icon: 'i-index', name: index.name, sub: index.table,
			title: (index.unique ? 'Unique index on ' : 'Index on ') + index.table });
		return el('li', { role: 'none' }, [b]);
	}

	function onNodeClick(o) {
		state.selected = o.key;
		if (o.expandable) {
			var wasOpen = state.expanded.has(o.key);
			if (wasOpen) state.expanded.delete(o.key); else state.expanded.add(o.key);
			if (!wasOpen && o.table && !state.columns[o.key]) {
				loadColumns(o);
			}
		}
		renderTree();
		var again = $('tree').querySelector('[data-key="' + cssEscape(o.key) + '"]');
		if (again) again.focus();
	}

	function cssEscape(text) { return text.replace(/\\/g, '\\\\').replace(/"/g, '\\"'); }

	function loadColumns(o) {
		var t = o.table;
		var url = '/api/console/columns?catalog=' + encodeURIComponent(t.db) + '&schema=' + encodeURIComponent(t.schema) + '&table=' + encodeURIComponent(t.name);
		api(url).then(function (cols) { state.columns[o.key] = cols; }, function (e) {
			state.columns[o.key] = [];
			showError(e.message);
		}).then(renderTree);
	}

	$('filter').addEventListener('input', renderTree);
	$('refreshBtn').addEventListener('click', function () { state.columns = {}; loadCatalog(false); });

	function openTable(db, schema, table, multiDb) {
		$('sql').value = U.selectAll(schema, table, 100, multiDb ? db : null);
		onEditorChange();
		run();
	}


	// ---------- S3 storages ----------

	function loadStorages() {
		return api('/api/console/storages').then(function (list) {
			state.storages = list;
			renderStorages();
		}, function (e) {
			state.storages = [];
			var box = $('storageList');
			box.textContent = '';
			box.appendChild(el('div', { class: 'storage-empty', text: e.message }));
		});
	}

	function renderStorages() {
		var box = $('storageList');
		box.textContent = '';
		if (state.storageWarning) {
			box.appendChild(el('p', { class: 'storage-warning', role: 'status', text: state.storageWarning }));
		}
		if (state.storages.length === 0) {
			var empty = el('div', { class: 'storage-empty' }, [
				el('p', { text: 'No storage yet. Add an S3 bucket to give this account access to your files.' })
			]);
			box.appendChild(empty);
			return;
		}
		state.storages.forEach(function (s) {
			var edit = el('button', { type: 'button', class: 'icon-btn', title: 'Edit ' + s.name, 'aria-label': 'Edit ' + s.name }, [icon('i-edit')]);
			var remove = el('button', { type: 'button', class: 'icon-btn', title: 'Remove ' + s.name, 'aria-label': 'Remove ' + s.name }, [icon('i-trash')]);
			edit.addEventListener('click', function () { openStorageForm(s); });
			remove.addEventListener('click', function () { openRemove(s); });
			var where = s.url + (s.endpoint ? ' at ' + s.endpoint : '');
			var row = el('div', { class: 'storage', title: where }, [
				icon('i-bucket', 'kind'),
				el('div', { class: 'text' }, [el('span', { class: 's-name', text: s.name }), el('span', { class: 's-url', text: s.url })]),
				el('div', { class: 'actions' }, [edit, remove])
			]);
			row.addEventListener('dblclick', function () { openStorageForm(s); });
			box.appendChild(row);
		});
		box.appendChild(el('p', { class: 'storage-hint', text: 'Read files in SQL with s3://bucket/path, for example read_csv(\'s3://' + state.storages[0].bucket + '/file.csv\').' }));
	}

	var dialog = $('storageDialog');
	var editing = null;           // the storage being edited, or null when adding

	function field(id) { return $(id); }

	function showFormError(id, message) {
		var box = $(id);
		box.textContent = message || '';
		box.hidden = !message;
	}

	function openStorageForm(storage) {
		editing = storage || null;
		var form = $('storageForm');
		form.reset();
		showFormError('storageError', '');
		$('storageTitle').textContent = editing ? 'Edit ' + editing.name : 'Add S3 storage';
		$('stName').value = editing ? editing.name : '';
		$('stName').disabled = !!editing;
		$('stBucket').value = editing ? editing.bucket : '';
		$('stRegion').value = editing ? editing.region : '';
		$('stPrefix').value = editing ? editing.pathPrefix : '';
		$('stKey').value = editing ? editing.accessKeyId : '';
		$('stSecret').value = '';
		$('stSecret').required = !editing;
		$('stSecret').placeholder = editing ? 'Leave empty to keep the current secret' : '';
		$('stSecretHelp').textContent = editing ? 'A secret is stored. Type a new one only to replace it.' : 'Stored encrypted. It is never shown again.';
		$('stEndpoint').value = editing && editing.endpoint ? editing.endpoint : '';
		$('stStyle').value = editing && editing.endpoint ? editing.urlStyle : '';
		$('stSsl').checked = editing ? editing.useSsl : true;
		$('stAdvanced').open = !!(editing && editing.endpoint);
		$('storageSave').disabled = false;
		dialog.showModal();
		(editing ? $('stBucket') : $('stName')).focus();
	}

	function readForm() {
		var body = {
			name: $('stName').value.trim(),
			bucket: $('stBucket').value.trim(),
			region: $('stRegion').value.trim(),
			pathPrefix: $('stPrefix').value.trim(),
			accessKeyId: $('stKey').value.trim(),
			secretAccessKey: $('stSecret').value,
			endpoint: $('stEndpoint').value.trim(),
			urlStyle: $('stStyle').value,
			useSsl: $('stSsl').checked
		};
		return body;
	}

	$('addStorageBtn').addEventListener('click', function () { openStorageForm(null); });
	$('storageCancel').addEventListener('click', function () { dialog.close(); });

	$('storageForm').addEventListener('submit', function (event) {
		event.preventDefault();
		showFormError('storageError', '');
		var body = readForm();
		var save = $('storageSave');
		save.disabled = true;
		var request = editing
			? api('/api/console/storages/' + encodeURIComponent(editing.name), { method: 'PUT', body: body })
			: api('/api/console/storages', { method: 'POST', body: body });
		request.then(function (saved) {
			state.storageWarning = saved && saved.warning ? saved.warning : null;
			dialog.close();
			return loadStorages();
		}, function (e) {
			showFormError('storageError', e.message);
			if (/endpoint|url style/i.test(e.message)) $('stAdvanced').open = true;
		}).then(function () { save.disabled = false; });
	});

	var removeDialog = $('removeDialog');
	var removing = null;

	function openRemove(storage) {
		removing = storage;
		showFormError('removeError', '');
		$('removeText').textContent = 'Remove "' + storage.name + '" (' + storage.url + ')? Its saved keys are deleted. The files in the bucket are not touched.';
		$('removeConfirm').disabled = false;
		removeDialog.showModal();
		$('removeCancel').focus();
	}

	$('removeCancel').addEventListener('click', function () { removeDialog.close(); });
	$('removeForm').addEventListener('submit', function (event) {
		event.preventDefault();
		var confirm = $('removeConfirm');
		confirm.disabled = true;
		api('/api/console/storages/' + encodeURIComponent(removing.name), { method: 'DELETE' }).then(function (done) {
			state.storageWarning = done && done.warning ? done.warning : null;
			removeDialog.close();
			return loadStorages();
		}, function (e) {
			showFormError('removeError', e.message);
		}).then(function () { confirm.disabled = false; });
	});

	// A click on the dark backdrop closes a dialog
	[dialog, removeDialog].forEach(function (d) {
		d.addEventListener('mousedown', function (event) { if (event.target === d) d.close(); });
	});

	// ---------- editor ----------

	var sqlBox = $('sql');
	var hl = $('hl');

	function onEditorChange() {
		hl.firstChild.innerHTML = U.highlight(sqlBox.value) + '\n';
		hl.scrollTop = sqlBox.scrollTop;
		hl.scrollLeft = sqlBox.scrollLeft;
		store('oc.sql', sqlBox.value);
	}

	sqlBox.addEventListener('input', onEditorChange);
	sqlBox.addEventListener('scroll', function () { hl.scrollTop = sqlBox.scrollTop; hl.scrollLeft = sqlBox.scrollLeft; });
	sqlBox.addEventListener('keydown', function (event) {
		if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
			event.preventDefault();
			run();
		}
	});
	$('runBtn').addEventListener('click', run);

	// ---------- run and show results ----------

	function run() {
		if (state.running) return;
		var text = sqlBox.value;
		var selected = text.slice(sqlBox.selectionStart, sqlBox.selectionEnd);
		var sql = selected.trim() ? selected : text;
		if (!sql.trim()) {
			showError('There is no SQL to run. Type a statement in the editor.');
			return;
		}

		state.running = true;
		$('runBtn').disabled = true;
		setStatus('Running…');
		var started = performance.now();

		api('/api/console/query', { method: 'POST', body: { sql: sql, maxRows: parseInt($('maxRows').value, 10) } })
			.then(function (result) {
				result.clientMs = Math.round(performance.now() - started);
				state.result = result;
				showResult(result);
				if (result.columns.length === 0 || result.statements > 1 || /\b(create|drop|alter|attach|detach|rename)\b/i.test(sql)) {
					loadCatalog(false); // the script may have changed the structure
				}
			}, function (e) {
				showError(e.message);
			})
			.then(function () {
				state.running = false;
				$('runBtn').disabled = false;
			});
	}

	function setStatus(html, text) {
		var status = $('resultStatus');
		status.textContent = '';
		if (html) {
			var strong = el('strong', { text: html });
			status.appendChild(strong);
		}
		if (text) status.appendChild(document.createTextNode(text));
	}

	function showError(message) {
		state.result = null;
		state.renderId++;
		setStatus('Failed');
		$('copyBtn').hidden = true;
		$('csvBtn').hidden = true;
		var body = $('resultBody');
		body.textContent = '';
		body.appendChild(el('div', { class: 'error-box', role: 'alert', text: message }));
	}

	function showResult(r) {
		var body = $('resultBody');
		body.textContent = '';
		var renderId = ++state.renderId;
		var many = r.statements > 1 ? ', result of the last of ' + r.statements + ' statements' : '';
		var time = ' in ' + r.elapsedMs + ' ms';

		if (r.columns.length === 0) {
			setStatus(r.message, time + many);
			$('copyBtn').hidden = true;
			$('csvBtn').hidden = true;
			body.appendChild(el('div', { class: 'ok-box', text: r.message + '.' }));
			return;
		}

		setStatus(plural(r.rowCount, 'row') + (r.truncated ? ' shown, more exist' : ''), time + many);
		$('copyBtn').hidden = false;
		$('csvBtn').hidden = false;

		if (r.rowCount === 0) {
			body.appendChild(el('div', { class: 'empty', text: 'The query ran and returned no rows.' }));
			return;
		}

		var numeric = r.columns.map(function (c) { return U.isNumeric(c.type); });
		var head = el('tr', {}, [el('th', { class: 'rownum', text: '#' })]);
		r.columns.forEach(function (c, i) {
			head.appendChild(el('th', { class: numeric[i] ? 'num' : '' }, [document.createTextNode(c.name), el('span', { class: 'type', text: c.type })]));
		});
		var tbody = el('tbody');
		body.appendChild(el('table', { class: 'grid' }, [el('thead', {}, [head]), tbody]));

		var next = 0;
		var CHUNK = 400;
		(function addRows() {
			if (renderId !== state.renderId) return; // a newer result replaced this one
			var frag = document.createDocumentFragment();
			var end = Math.min(next + CHUNK, r.rows.length);
			for (; next < end; next++) {
				var tr = el('tr', {}, [el('td', { class: 'rownum', text: String(next + 1) })]);
				r.rows[next].forEach(function (value, i) {
					var cell = U.cellText(value);
					var td = el('td', { text: cell.text });
					if (cell.isNull) td.className = 'null';
					else if (numeric[i]) td.className = 'num';
					if (cell.text.length > 40) td.title = cell.text.length > 600 ? cell.text.slice(0, 600) + '…' : cell.text;
					tr.appendChild(td);
				});
				frag.appendChild(tr);
			}
			tbody.appendChild(frag);
			if (next < r.rows.length) setTimeout(addRows, 0);
		})();
	}

	function csvOfResult() { return U.toCsv(state.result.columns, state.result.rows); }

	$('copyBtn').addEventListener('click', function () {
		if (!state.result) return;
		var csv = csvOfResult();
		var done = function () { var b = $('copyBtn'); b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy as CSV'; }, 1500); };
		if (navigator.clipboard && window.isSecureContext) {
			navigator.clipboard.writeText(csv).then(done, function () { fallbackCopy(csv); done(); });
		} else {
			fallbackCopy(csv);
			done();
		}
	});

	function fallbackCopy(text) {
		var area = el('textarea', { style: 'position:fixed;opacity:0' });
		area.value = text;
		document.body.appendChild(area);
		area.select();
		try { document.execCommand('copy'); } catch (e) { /* nothing else to try */ }
		document.body.removeChild(area);
	}

	$('csvBtn').addEventListener('click', function () {
		if (!state.result) return;
		var blob = new Blob([csvOfResult()], { type: 'text/csv;charset=utf-8' });
		var a = el('a', { href: URL.createObjectURL(blob), download: 'result.csv' });
		document.body.appendChild(a);
		a.click();
		document.body.removeChild(a);
		setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
	});

	// ---------- resizing ----------

	function drag(handle, onMove) {
		handle.addEventListener('pointerdown', function (event) {
			handle.setPointerCapture(event.pointerId);
			handle.classList.add('dragging');
			document.body.style.userSelect = 'none';
		});
		handle.addEventListener('pointermove', function (event) {
			if (handle.hasPointerCapture(event.pointerId)) onMove(event);
		});
		var stop = function (event) {
			if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
			handle.classList.remove('dragging');
			document.body.style.userSelect = '';
		};
		handle.addEventListener('pointerup', stop);
		handle.addEventListener('pointercancel', stop);
	}

	function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

	function setSide(px) {
		var max = Math.min(640, $('workspace').clientWidth - 320);
		$('workspace').style.setProperty('--side-width', clamp(px, 200, Math.max(200, max)) + 'px');
	}

	function setEditor(px) {
		var h = $('main').getBoundingClientRect().height;
		$('main').style.setProperty('--editor-height', clamp(px, 110, Math.max(110, h - 140)) + 'px');
	}

	function setStructure(px) {
		var h = $('side').getBoundingClientRect().height;
		$('side').style.setProperty('--structure-height', clamp(px, 120, Math.max(120, h - 110)) + 'px');
	}

	drag($('splitSide'), function (event) { setStructure(event.clientY - $('side').getBoundingClientRect().top); });
	$('splitSide').addEventListener('keydown', function (event) {
		var height = $('structure').getBoundingClientRect().height;
		if (event.key === 'ArrowUp') { setStructure(height - 24); event.preventDefault(); }
		if (event.key === 'ArrowDown') { setStructure(height + 24); event.preventDefault(); }
	});

	drag($('splitV'), function (event) { setSide(event.clientX - $('workspace').getBoundingClientRect().left); });
	drag($('splitH'), function (event) { setEditor(event.clientY - $('main').getBoundingClientRect().top); });

	$('splitV').addEventListener('keydown', function (event) {
		var width = $('side').getBoundingClientRect().width;
		if (event.key === 'ArrowLeft') { setSide(width - 24); event.preventDefault(); }
		if (event.key === 'ArrowRight') { setSide(width + 24); event.preventDefault(); }
	});
	$('splitH').addEventListener('keydown', function (event) {
		var height = $('editorWrap').getBoundingClientRect().height;
		if (event.key === 'ArrowUp') { setEditor(height - 24); event.preventDefault(); }
		if (event.key === 'ArrowDown') { setEditor(height + 24); event.preventDefault(); }
	});

	// ---------- theme ----------

	function applyTheme(theme) {
		if (theme === 'light' || theme === 'dark') document.documentElement.setAttribute('data-theme', theme);
	}

	$('themeBtn').addEventListener('click', function () {
		var current = document.documentElement.getAttribute('data-theme') ||
			(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
		var next = current === 'dark' ? 'light' : 'dark';
		applyTheme(next);
		store('oc.theme', next);
	});

	// ---------- start ----------

	applyTheme(store('oc.theme'));
	var saved = store('oc.sql');
	sqlBox.value = saved !== null && saved !== '' ? saved : 'SELECT * FROM information_schema.tables;\n';
	onEditorChange();

	state.token = tokenStore();
	if (state.token) {
		startApp().catch(function () { showLogin(); });
	} else {
		showLogin();
	}
})();
