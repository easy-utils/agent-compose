package com.agent.app.platform

/**
 * Runtime loader for `@sqlite.org/sqlite-wasm`.
 *
 * Why NOT `@JsModule`: the Kotlin/Wasm + webpack combination rewrites the
 * module's own `new URL("sqlite3.wasm", import.meta.url)` to a **build-time
 * absolute `file:///…` URL**, so after deploy the wasm/OPFS-proxy URLs point at
 * the build machine and OPFS init fails. Loading the package's ESM build at
 * RUNTIME via `import('./sqlite/index.mjs')` keeps `import.meta.url` pointing at
 * the served file, so its sibling asset URLs resolve correctly.
 *
 * The package's dist files (`index.mjs`, `sqlite3.wasm`,
 * `sqlite3-opfs-async-proxy.js`) are shipped from `wasmJsMain/resources/sqlite/`
 * (copied from webui's canonical `@sqlite.org/sqlite-wasm` 3.53.4-build1).
 *
 * [sqliteInitPromise] returns a JS Promise that resolves to the module's
 * `sqlite3InitModule` function — the bridge awaits it, then calls it.
 */
@JsFun(
    """
() => {
  if (!window.__agentSqliteInit) {
    // The dynamic import is built through `new Function` ON PURPOSE: webpack's
    // static analyzer must NOT see/resolve it, otherwise it bundles the module
    // (rewriting its import.meta.url to a build-time file:// URL). Evaluating
    // the import at runtime keeps the asset URLs relative to the served file.
    const dynImport = new Function("u", "return import(u)");
    window.__agentSqliteInit = dynImport("./sqlite/index.mjs").then(
      (m) => m.default || m.sqlite3InitModule || m,
    );
  }
  return window.__agentSqliteInit;
}
""",
)
external fun sqliteInitPromise(): JsAny
