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
		queryId: null,            // id of the query this tab is running, to kill it
		renderId: 0,
		storages: [],
		storageWarning: null,
		bucket: {},               // storage name -> folder path -> { folders, files, nextToken, loading, error }
		s3open: new Set(),
		epoch: 0,                 // bumped on every sign in and sign out; replies from an older epoch are dropped
		history: [],              // past queries of the signed-in user, newest first
		historySize: 10
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
					var error = new Error(message);
					error.status = res.status;
					error.data = data;
					throw error;
				}
				return data;
			});
		}, function () {
			throw new Error('Cannot reach the server. Check that OpenCanard is running.');
		});
	}

	// ---------- sign in ----------

	/**
	 * Puts every panel back to its empty state, so nothing from a previous session (an error in the tree, a
	 * failed query, a result of another user) is still on screen after signing in or out.
	 */
	function resetPanels() {
		state.epoch++;
		state.session = null;
		state.catalog = null;
		state.columns = {};
		state.expanded = new Set();
		state.selected = null;
		state.result = null;
		state.renderId++;
		state.queryId = null;
		setRunning(false);
		state.history = [];
		state.storages = [];
		state.storageWarning = null;
		state.bucket = {};
		state.s3open = new Set();
		firstLoad = true;
		['queriesDialog', 'storageDialog', 'removeDialog'].forEach(function (id) {
			var d = $(id);
			if (d && d.open) d.close(); // closing the running-queries dialog also stops its refresh timer
		});
		$('storageList').textContent = '';
		$('refreshBtn').classList.remove('busy');
		$('filter').value = '';
		$('tree').textContent = '';
		setStatus(null, 'Nothing run yet');
		$('copyBtn').hidden = true;
		$('csvBtn').hidden = true;
		$('resultBody').textContent = '';
		closeHistory();
		$('historyList').textContent = '';
	}

	function showLogin(message) {
		state.token = null;
		tokenStore(null);
		resetPanels();
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
		return api('/api/session').then(function (session) {
			resetPanels();
			state.session = session;
			state.historySize = typeof session.historySize === 'number' ? Math.max(0, session.historySize) : 10;
			state.history = loadHistory();
			$('history').hidden = state.historySize === 0;
			$('loginError').hidden = true;
			$('loginError').textContent = '';
			$('login').hidden = true;
			$('app').hidden = false;
			$('who').textContent = session.username + (session.admin ? ' (admin)' : '');
			$('connLabel').textContent = session.admin ? 'Metadata database' : 'Personal database';
			$('sql').focus();
			loadStorages();
			return loadCatalog(true);
		});
	}

	// ---------- structure tree ----------

	var firstLoad = true;

	function loadCatalog(initial) {
		var btn = $('refreshBtn');
		var epoch = state.epoch;
		btn.classList.add('busy');
		return api('/api/catalog').then(function (catalog) {
			if (epoch !== state.epoch) return;
			state.catalog = catalog;
			if (initial || firstLoad) expandDefaults(catalog);
			firstLoad = false;
			renderTree();
		}).catch(function (e) {
			if (epoch !== state.epoch) return;
			state.catalog = null;
			var tree = $('tree');
			tree.textContent = '';
			tree.appendChild(el('div', { class: 'tree-empty', text: e.message }));
		}).then(function () { if (epoch === state.epoch) btn.classList.remove('busy'); });
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
		var url = '/api/columns?catalog=' + encodeURIComponent(t.db) + '&schema=' + encodeURIComponent(t.schema) + '&table=' + encodeURIComponent(t.name);
		var epoch = state.epoch;
		api(url).then(function (cols) {
			if (epoch === state.epoch) state.columns[o.key] = cols;
		}, function (e) {
			if (epoch !== state.epoch) return;
			state.columns[o.key] = [];
			showError(e.message);
		}).then(function () { if (epoch === state.epoch) renderTree(); });
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
		var epoch = state.epoch;
		return api('/api/storages').then(function (list) {
			if (epoch !== state.epoch) return;
			state.storages = list;
			renderStorages();
		}, function (e) {
			if (epoch !== state.epoch) return;
			state.storages = [];
			var box = $('storageList');
			box.textContent = '';
			box.appendChild(el('div', { class: 'storage-empty', text: e.message }));
		});
	}

	function renderStorages() {
		var box = $('storageList');
		var scroll = box.scrollTop;
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
			var storageKey = key('s3', s.name, '');
			var open = state.s3open.has(storageKey);
			var reload = el('button', { type: 'button', class: 'icon-btn', title: 'Reload the contents of ' + s.name, 'aria-label': 'Reload the contents of ' + s.name }, [icon('i-refresh')]);
			var edit = el('button', { type: 'button', class: 'icon-btn', title: 'Edit ' + s.name, 'aria-label': 'Edit ' + s.name }, [icon('i-edit')]);
			reload.addEventListener('click', function () {
				// forget what was listed for this bucket only; it opens and its open folders are listed again
				delete state.bucket[s.name];
				state.s3open.add(storageKey);
				renderStorages();
			});
			var remove = el('button', { type: 'button', class: 'icon-btn', title: 'Remove ' + s.name, 'aria-label': 'Remove ' + s.name }, [icon('i-trash')]);
			edit.addEventListener('click', function () { openStorageForm(s); });
			remove.addEventListener('click', function () { openRemove(s); });
			var where = s.url + (s.endpoint ? ' at ' + s.endpoint : '');
			var main = el('button', { type: 'button', class: 'storage-main', 'aria-expanded': String(open), title: where }, [
				icon('i-chevron', 'chev'),
				icon('i-bucket', 'kind'),
				el('span', { class: 'text' }, [el('span', { class: 's-name', text: s.name }), el('span', { class: 's-url' + (s.secretReadable === false ? ' bad' : ''), text: s.secretReadable === false ? 'Secret must be entered again' : s.url })])
			]);
			main.addEventListener('click', function () {
				if (state.s3open.has(storageKey)) state.s3open.delete(storageKey); else state.s3open.add(storageKey);
				renderStorages();
			});
			box.appendChild(el('div', { class: 'storage' }, [main, el('div', { class: 'actions' }, [reload, edit, remove])]));
			if (open) {
				var rows = [];
				bucketRows(s, '', 1, rows);
				rows.forEach(function (r) { box.appendChild(r); });
			}
		});
		box.appendChild(el('p', { class: 'storage-hint', text: 'Read files in SQL with s3://bucket/path, for example read_csv(\'s3://' + state.storages[0].bucket + '/file.csv\').' }));
		box.scrollTop = scroll;
	}

	// ---------- bucket contents ----------

	function bucketRows(s, path, depth, into) {
		var cache = state.bucket[s.name] || (state.bucket[s.name] = {});
		var level = cache[path];
		if (!level) {
			loadBucket(s, path, null);
			level = cache[path];
		}
		level.folders.forEach(function (f) {
			var folderKey = key('s3', s.name, f.path);
			var open = state.s3open.has(folderKey);
			var btn = bucketNode(depth, 'i-schema', f.name.replace(/\/$/, ''), '', open);
			btn.addEventListener('click', function () {
				if (state.s3open.has(folderKey)) state.s3open.delete(folderKey); else state.s3open.add(folderKey);
				renderStorages();
			});
			into.push(btn);
			if (open) bucketRows(s, f.path, depth + 1, into);
		});
		level.files.forEach(function (file) {
			var btn = bucketNode(depth, 'i-file', file.name, U.formatBytes(file.size), null);
			btn.title = file.url + '\n' + file.modified;
			var sample = U.sampleQuery(file.url, 100);
			if (!sample) { into.push(btn); return; }
			var run = function () { $('sql').value = sample; onEditorChange(); runQuery(); };
			btn.addEventListener('dblclick', run);
			var play = el('button', { type: 'button', class: 'open-btn', title: 'Select rows from ' + file.name, 'aria-label': 'Select rows from ' + file.name }, [icon('i-play')]);
			play.addEventListener('click', run);
			into.push(el('div', { class: 'row' }, [btn, play]));
		});
		if (level.loading && level.folders.length + level.files.length === 0) {
			into.push(bucketNote(depth, 'Loading…'));
		} else if (level.error) {
			var retry = el('button', { type: 'button', class: 'link-btn', text: 'Try again' });
			retry.addEventListener('click', function () { loadBucket(s, path, level.nextToken); renderStorages(); });
			var note = bucketNote(depth, level.error);
			note.classList.add('bad');
			note.appendChild(document.createTextNode(' '));
			note.appendChild(retry);
			into.push(note);
		} else if (level.nextToken) {
			var more = el('button', { type: 'button', class: 'link-btn', text: level.loading ? 'Loading…' : 'Load more' });
			more.addEventListener('click', function () { loadBucket(s, path, level.nextToken); renderStorages(); });
			var moreRow = bucketNote(depth, '');
			moreRow.appendChild(more);
			into.push(moreRow);
		} else if (level.folders.length + level.files.length === 0 && !level.loading) {
			into.push(bucketNote(depth, 'Empty'));
		}
	}

	function bucketNode(depth, iconId, name, sub, open) {
		var btn = el('button', { type: 'button', class: 'node bucket-node' });
		btn.style.setProperty('--depth', depth + 1);
		if (open !== null) btn.setAttribute('aria-expanded', String(open));
		btn.appendChild(icon('i-chevron', 'chev' + (open === null ? ' none' : '')));
		btn.appendChild(icon(iconId, 'kind'));
		btn.appendChild(el('span', { class: 'name', text: name }));
		if (sub) btn.appendChild(el('span', { class: 'sub', text: sub }));
		return btn;
	}

	function bucketNote(depth, text) {
		var p = el('div', { class: 'bucket-note', text: text });
		p.style.setProperty('--depth', depth + 1);
		return p;
	}

	function loadBucket(s, path, token) {
		var cache = state.bucket[s.name] || (state.bucket[s.name] = {});
		var level = cache[path];
		if (!level || !token) {
			level = cache[path] = { folders: [], files: [], nextToken: null, loading: true, error: null };
		} else {
			level.loading = true;
			level.error = null;
		}
		var url = '/api/storages/' + encodeURIComponent(s.name) + '/objects?path=' + encodeURIComponent(path) +
			(token ? '&token=' + encodeURIComponent(token) : '');
		api(url).then(function (r) {
			level.folders = token ? level.folders.concat(r.folders) : r.folders;
			level.files = token ? level.files.concat(r.files) : r.files;
			level.nextToken = r.nextToken;
		}, function (e) {
			level.error = e.message;
		}).then(function () {
			level.loading = false;
			if (state.bucket[s.name] === cache && cache[path] === level) renderStorages();
		});
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
		showTest('', true);
		$('storageTitle').textContent = editing ? 'Edit ' + editing.name : 'Add S3 storage';
		$('stName').value = editing ? editing.name : '';
		$('stName').disabled = !!editing;
		$('stBucket').value = editing ? editing.bucket : '';
		$('stRegion').value = editing ? editing.region : '';
		$('stPrefix').value = editing ? editing.pathPrefix : '';
		$('stKey').value = editing ? editing.accessKeyId : '';
		$('stSecret').value = '';
		$('stSecret').required = false;
		var hasSecret = !!(editing && !editing.anonymous);
		$('stSecret').placeholder = hasSecret ? 'Leave empty to keep the current secret' : '';
		$('stSecretHelp').textContent = hasSecret && editing.secretReadable === false ? 'The saved secret cannot be read any more. Type it again.' : hasSecret ? 'A secret is stored. Type a new one only to replace it.' : 'Stored encrypted. It is never shown again.';
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

	// Test connection: tries the values of the form without saving them
	function showTest(message, ok) {
		var box = $('storageTestResult');
		box.textContent = message || '';
		box.hidden = !message;
		box.className = 'test-result ' + (ok ? 'ok' : 'bad');
	}

	$('storageTest').addEventListener('click', function () {
		showFormError('storageError', '');
		showTest('Connecting…', true);
		var btn = $('storageTest');
		btn.disabled = true;
		var body = readForm();
		body.name = editing ? editing.name : body.name;
		api('/api/storages/test', { method: 'POST', body: body }).then(function (r) {
			showTest(r.message, r.ok);
		}, function (e) {
			showTest(e.message, false);
		}).then(function () { btn.disabled = false; });
	});
	$('storageForm').addEventListener('input', function () { showTest('', true); });
	$('storageCancel').addEventListener('click', function () { dialog.close(); });

	$('storageForm').addEventListener('submit', function (event) {
		event.preventDefault();
		showFormError('storageError', '');
		var body = readForm();
		var save = $('storageSave');
		save.disabled = true;
		var request = editing
			? api('/api/storages/' + encodeURIComponent(editing.name), { method: 'PUT', body: body })
			: api('/api/storages', { method: 'POST', body: body });
		request.then(function (saved) {
			state.storageWarning = saved && saved.warning ? saved.warning : null;
			delete state.bucket[editing ? editing.name : body.name];
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
		api('/api/storages/' + encodeURIComponent(removing.name), { method: 'DELETE' }).then(function (done) {
			state.storageWarning = done && done.warning ? done.warning : null;
			delete state.bucket[removing.name];
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

	function runQuery() { run(); }

	/** Random id for a query, so it can be killed before its answer arrives (crypto.randomUUID needs https). */
	function newQueryId() {
		var bytes = new Uint8Array(16);
		window.crypto.getRandomValues(bytes);
		var hex = Array.prototype.map.call(bytes, function (b) { return (b < 16 ? '0' : '') + b.toString(16); }).join('');
		return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
	}

	function setRunning(on) {
		state.running = on;
		$('runBtn').disabled = on;
		var kill = $('killBtn');
		kill.hidden = !on;
		kill.disabled = false;
		kill.lastChild.textContent = 'Kill';
	}

	function killQuery(id) {
		return api('/api/queries/' + encodeURIComponent(id) + '/cancel', { method: 'POST' });
	}

	$('killBtn').addEventListener('click', function () {
		if (!state.running || !state.queryId) return;
		var kill = $('killBtn');
		kill.disabled = true;
		kill.lastChild.textContent = 'Killing…';
		setStatus('Cancelling…');
		killQuery(state.queryId).catch(function (e) {
			// 404: it finished in the meantime, its result is on its way
			if (e.status !== 404) {
				kill.disabled = false;
				kill.lastChild.textContent = 'Kill';
				setStatus('Running…', ' (could not cancel: ' + e.message + ')');
			}
		});
	});

	function run() {
		if (state.running) return;
		var text = sqlBox.value;
		var selected = text.slice(sqlBox.selectionStart, sqlBox.selectionEnd);
		var sql = selected.trim() ? selected : text;
		if (!sql.trim()) {
			showError('There is no SQL to run. Type a statement in the editor.');
			return;
		}

		var queryId = newQueryId();
		state.queryId = queryId;
		setRunning(true);
		setStatus('Running…');
		var started = performance.now();
		var epoch = state.epoch;

		api('/api/query', { method: 'POST', body: { sql: sql, maxRows: parseInt($('maxRows').value, 10), queryId: queryId } })
			.then(function (result) {
				if (epoch !== state.epoch) return; // signed out (or in as someone else) while it ran
				result.clientMs = Math.round(performance.now() - started);
				addToHistory(sql, true, result.columns.length ? plural(result.rowCount, 'row') + (result.truncated ? '+' : '') : result.message);
				state.result = result;
				showResult(result);
				if (result.columns.length === 0 || result.statements > 1 || /\b(create|drop|alter|attach|detach|rename)\b/i.test(sql)) {
					loadCatalog(false); // the script may have changed the structure
				}
			}, function (e) {
				if (epoch !== state.epoch) return;
				var cancelled = e.data && e.data.cancelled;
				addToHistory(sql, false, cancelled ? 'Cancelled' : 'Failed');
				if (cancelled) showCancelled(e.message, /;\s*\S/.test(sql)); // more than one statement
				else showError(e.message);
			})
			.then(function () {
				if (epoch !== state.epoch) return;
				state.queryId = null;
				setRunning(false);
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

	function showCancelled(message, script) {
		state.result = null;
		state.renderId++;
		setStatus('Cancelled');
		$('copyBtn').hidden = true;
		$('csvBtn').hidden = true;
		var body = $('resultBody');
		body.textContent = '';
		body.appendChild(el('div', { class: 'cancel-box', role: 'status', text: message + (script ? ' The statements of the script that ran before it are not undone.' : '') }));
	}

	// ---------- running queries ----------

	var queriesDialog = $('queriesDialog');
	var queriesTimer = null;
	var killing = new Set();       // ids whose Kill was clicked in the popup

	function formatDuration(ms) {
		var s = Math.floor(ms / 1000);
		if (s < 60) return (ms < 10000 ? (ms / 1000).toFixed(1) : String(s)) + ' s';
		var m = Math.floor(s / 60);
		if (m < 60) return m + ' min ' + (s % 60) + ' s';
		return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
	}

	function formatTime(iso) {
		var d = new Date(iso);
		return isNaN(d) ? '' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
	}

	function loadQueries() {
		var btn = $('queriesRefresh');
		btn.classList.add('busy');
		return api('/api/queries').then(function (r) {
			showFormError('queriesError', '');
			renderQueries(r);
		}, function (e) {
			showFormError('queriesError', e.message);
		}).then(function () { btn.classList.remove('busy'); });
	}

	function renderQueries(r) {
		var box = $('queriesList');
		var focused = document.activeElement && box.contains(document.activeElement) ? document.activeElement.getAttribute('data-id') : null;
		$('queriesScope').textContent = r.allUsers ? 'All users (you are an admin)' : 'Your queries';
		box.textContent = '';
		var live = new Set(r.queries.map(function (q) { return q.id; }));
		killing.forEach(function (id) { if (!live.has(id)) killing.delete(id); });

		if (r.queries.length === 0) {
			box.appendChild(el('div', { class: 'queries-empty', text: r.allUsers ? 'No query is running on the server.' : 'You have no query running.' }));
			return;
		}

		var head = el('tr', {}, []);
		var cols = (r.allUsers ? [['User', 'w-user']] : []).concat([['Source', 'w-source'], ['Started', 'w-time'],
			['Running for', 'w-time'], ['SQL', ''], ['', 'w-action']]);
		cols.forEach(function (c) {
			var th = el('th', { text: c[0] });
			if (c[1]) th.className = c[1];
			if (!c[0]) th.setAttribute('aria-label', 'Kill');
			head.appendChild(th);
		});
		var tbody = el('tbody');
		r.queries.forEach(function (q) {
			var mine = q.id === state.queryId;
			var tr = el('tr', { class: q.state === 'cancelling' ? 'is-cancelling' : '' });
			if (r.allUsers) tr.appendChild(el('td', { class: 'q-user', text: q.user }));
			var source = el('td', { class: 'q-source', text: q.source });
			if (mine) source.appendChild(el('span', { class: 'q-tag', text: 'this tab' }));
			tr.appendChild(source);
			tr.appendChild(el('td', { class: 'q-time', text: formatTime(q.startedAt) }));
			tr.appendChild(el('td', { class: 'q-time num', text: formatDuration(q.elapsedMs) }));
			var sqlText = q.sql.replace(/\s+/g, ' ').trim();
			tr.appendChild(el('td', { class: 'q-sql', title: q.sql, text: sqlText }));
			var pending = q.state === 'cancelling' || killing.has(q.id);
			var kill = el('button', { type: 'button', class: 'btn kill small', 'data-id': q.id,
				'aria-label': 'Kill the query of ' + q.user + ' started at ' + formatTime(q.startedAt) }, [icon('i-stop'), document.createTextNode(pending ? 'Killing…' : 'Kill')]);
			kill.disabled = pending;
			kill.addEventListener('click', function () {
				killing.add(q.id);
				kill.disabled = true;
				kill.lastChild.textContent = 'Killing…';
				if (mine) setStatus('Cancelling…');
				killQuery(q.id).then(loadQueries, function (e) {
					killing.delete(q.id);
					if (e.status === 404) { loadQueries(); return; } // it finished meanwhile
					showFormError('queriesError', e.message);
					kill.disabled = false;
					kill.lastChild.textContent = 'Kill';
				});
			});
			tr.appendChild(el('td', { class: 'q-action' }, [kill]));
			tbody.appendChild(tr);
		});
		box.appendChild(el('table', { class: 'grid queries' }, [el('thead', {}, [head]), tbody]));
		if (focused) {
			var again = box.querySelector('[data-id="' + cssEscape(focused) + '"]');
			if (again) again.focus();
		}
	}

	function stopQueriesTimer() {
		if (queriesTimer) clearInterval(queriesTimer);
		queriesTimer = null;
	}

	$('queriesBtn').addEventListener('click', function () {
		$('queriesList').textContent = '';
		$('queriesList').appendChild(el('div', { class: 'queries-empty', text: 'Loading…' }));
		showFormError('queriesError', '');
		queriesDialog.showModal();
		$('queriesClose').focus();
		loadQueries();
		stopQueriesTimer();
		queriesTimer = setInterval(function () { if (!document.hidden) loadQueries(); }, 2000);
	});
	$('queriesRefresh').addEventListener('click', loadQueries);
	$('queriesClose').addEventListener('click', function () { queriesDialog.close(); });
	queriesDialog.addEventListener('close', stopQueriesTimer);
	queriesDialog.addEventListener('mousedown', function (event) { if (event.target === queriesDialog) queriesDialog.close(); });

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

	// ---------- query history ----------

	// Kept in this browser's localStorage, one list per user; its length comes from console.history.size.

	function historyKey() { return 'oc.history.' + (state.session ? state.session.username : ''); }

	function loadHistory() {
		var list = [];
		try { list = JSON.parse(store(historyKey()) || '[]'); } catch (e) { list = []; }
		if (!Array.isArray(list)) list = [];
		return list.filter(function (h) { return h && typeof h.sql === 'string'; }).slice(0, state.historySize);
	}

	function saveHistory() {
		store(historyKey(), state.history.length ? JSON.stringify(state.history) : null);
	}

	function addToHistory(sql, ok, summary) {
		if (!state.session || state.historySize === 0) return;
		var text = sql.trim();
		if (!text) return;
		// the same query run again moves to the top instead of taking a second place
		state.history = state.history.filter(function (h) { return h.sql !== text; });
		state.history.unshift({ sql: text, at: Date.now(), ok: ok, summary: summary || '' });
		state.history = state.history.slice(0, state.historySize);
		saveHistory();
		if (!$('historyMenu').hidden) renderHistory();
	}

	function whenText(ms) {
		var d = new Date(ms);
		var today = new Date();
		var time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
		return d.toDateString() === today.toDateString() ? time : d.toLocaleDateString() + ' ' + time;
	}

	function renderHistory() {
		var list = $('historyList');
		list.textContent = '';
		$('historyTitle').textContent = 'Recent queries (' + state.history.length + ' of ' + state.historySize + ')';
		$('historyClear').hidden = state.history.length === 0;
		if (state.history.length === 0) {
			list.appendChild(el('li', { class: 'history-empty', text: 'Queries you run appear here.' }));
			return;
		}
		state.history.forEach(function (h) {
			var preview = h.sql.replace(/\s+/g, ' ');
			var load = el('button', { type: 'button', class: 'history-load', title: h.sql.length > 2000 ? h.sql.slice(0, 2000) + '…' : h.sql }, [
				el('span', { class: 'history-sql', text: preview }),
				el('span', { class: 'history-meta' + (h.ok ? '' : ' failed'), text: whenText(h.at) + (h.summary ? ' · ' + h.summary : '') })
			]);
			load.addEventListener('click', function () { useHistory(h, false); });
			var again = el('button', { type: 'button', class: 'history-run', title: 'Run again', 'aria-label': 'Run again: ' + preview.slice(0, 80) }, [icon('i-play')]);
			again.addEventListener('click', function () { useHistory(h, true); });
			list.appendChild(el('li', { class: 'history-item' }, [load, again]));
		});
	}

	function useHistory(h, runIt) {
		closeHistory();
		sqlBox.value = h.sql;
		onEditorChange();
		sqlBox.focus();
		sqlBox.setSelectionRange(sqlBox.value.length, sqlBox.value.length);
		if (runIt) run();
	}

	function openHistory() {
		renderHistory();
		$('historyMenu').hidden = false;
		$('historyBtn').setAttribute('aria-expanded', 'true');
		var first = $('historyList').querySelector('button');
		if (first) first.focus();
	}

	function closeHistory() {
		$('historyMenu').hidden = true;
		$('historyBtn').setAttribute('aria-expanded', 'false');
	}

	$('historyBtn').addEventListener('click', function () {
		if ($('historyMenu').hidden) openHistory(); else closeHistory();
	});
	$('historyClear').addEventListener('click', function () {
		state.history = [];
		saveHistory();
		renderHistory();
		$('historyBtn').focus();
	});
	document.addEventListener('click', function (event) {
		if (!$('historyMenu').hidden && !$('history').contains(event.target)) closeHistory();
	});
	$('history').addEventListener('keydown', function (event) {
		if (event.key === 'Escape' && !$('historyMenu').hidden) {
			closeHistory();
			$('historyBtn').focus();
			event.preventDefault();
		}
		if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && !$('historyMenu').hidden) {
			var items = Array.prototype.slice.call($('historyList').querySelectorAll('.history-load'));
			var i = items.indexOf(document.activeElement);
			var next = event.key === 'ArrowDown' ? Math.min(items.length - 1, i + 1) : Math.max(0, i - 1);
			if (items[next]) { items[next].focus(); event.preventDefault(); }
		}
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
