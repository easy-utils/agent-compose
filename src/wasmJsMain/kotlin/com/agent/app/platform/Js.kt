package com.agent.app.platform

// Wasm JS interop primitives (Kotlin/Wasm does NOT support `dynamic`/`js()`;
// it uses @JsFun + lambda callbacks). All browser APIs are funneled through
// these single-expression JS functions.

// ---- localStorage ----

@JsFun("(k) => { try { return localStorage.getItem(k); } catch(e) { return null; } }")
external fun jsLocalGet(k: String): String?

@JsFun("(k, v) => { try { localStorage.setItem(k, v); } catch(e) {} }")
external fun jsLocalSet(k: String, v: String)

@JsFun("(k) => { try { localStorage.removeItem(k); } catch(e) {} }")
external fun jsLocalRemove(k: String)

// ---- system language / color scheme (the "follow system" theme + language) ----

@JsFun("() => { try { return ((navigator.languages && navigator.languages[0]) || navigator.language || 'en'); } catch(e) { return 'en'; } }")
external fun jsNavigatorLanguage(): String

/** Whether the browser/OS language is Chinese. */
fun jsSystemLangZh(): Boolean = jsNavigatorLanguage().lowercase().startsWith("zh")

@JsFun("(cb) => { try { var mq = window.matchMedia('(prefers-color-scheme: dark)'); mq.addEventListener('change', function(e){ cb(e.matches); }); cb(mq.matches); } catch(e) {} }")
external fun jsInstallSystemDarkListener(cb: (Boolean) -> Unit)

// ---- small DOM helpers ----

@JsFun("(t, b) => b ? (t + '\\n\\n' + b) : t; ")
external fun jsJoinConfirm(t: String, b: String): String

@JsFun("(msg) => window.confirm(msg)")
external fun jsConfirm(msg: String): Boolean

@JsFun("(title, initial) => window.prompt(title, initial)")
external fun jsPrompt(title: String, initial: String): String?

@JsFun("(t) => { try { navigator.clipboard.writeText(t); } catch(e) {} }")
external fun jsWriteClipboard(t: String)

@JsFun("(s) => btoa(s)")
external fun jsBtoa(s: String): String

@JsFun("(s) => atob(s)")
external fun jsAtob(s: String): String

@JsFun("(s) => encodeURIComponent(s)")
external fun jsEncodeURIComponent(s: String): String

// ---- file picker: returns a JSON array [{name,mime,b64}] ----

@JsFun(
    """
(mime, cb) => {
  const input = document.createElement('input');
  input.type = 'file'; input.multiple = true;
  if (mime) input.accept = mime;
  input.onchange = () => {
    const files = input.files;
    if (!files || files.length === 0) { cb('[]'); return; }
    const out = new Array(files.length);
    let remaining = files.length;
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      const fr = new FileReader();
      fr.onload = () => {
        const u8 = new Uint8Array(fr.result);
        let bin = '';
        const CH = 0x8000;
        for (let k = 0; k < u8.length; k += CH) {
          bin += String.fromCharCode.apply(null, u8.subarray(k, k + CH));
        }
        out[i] = { name: f.name, mime: f.type || 'application/octet-stream', b64: btoa(bin) };
        if (--remaining === 0) cb(JSON.stringify(out));
      };
      fr.readAsArrayBuffer(f);
    }
  };
  input.click();
}
""",
)
external fun jsPickFiles(mime: String?, cb: (String) -> Unit)

// ---- voice recording (MediaRecorder) ----

@JsFun(
    """
(cb) => {
  if (!navigator.mediaDevices || !window.MediaRecorder) { cb(false); return; }
  navigator.mediaDevices.getUserMedia({ audio: true }).then(s => {
    const chunks = [];
    const r = new MediaRecorder(s);
    window.__voice = { rec: r, stream: s, chunks: chunks };
    r.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data); };
    r.start();
    cb(true);
  }).catch(e => cb(false));
}
""",
)
external fun jsVoiceStart(cb: (Boolean) -> Unit)

@JsFun(
    """
(cb) => {
  const v = window.__voice;
  if (!v) { cb(null); return; }
  v.rec.onstop = () => {
    try { v.stream.getTracks().forEach(t => t.stop()); } catch(e) {}
    const blob = new Blob(v.chunks, { type: 'audio/webm' });
    if (blob.size === 0) { cb(null); return; }
    const fr = new FileReader();
    fr.onload = () => {
      const u8 = new Uint8Array(fr.result);
      let bin = '';
      const CH = 0x8000;
      for (let k = 0; k < u8.length; k += CH) bin += String.fromCharCode.apply(null, u8.subarray(k, k + CH));
      cb(btoa(bin));
    };
    fr.readAsArrayBuffer(blob);
  };
  if (v.rec.state !== 'inactive') v.rec.stop(); else v.rec.onstop();
}
""",
)
external fun jsVoiceStop(cb: (String?) -> Unit)

@JsFun(
    """
() => {
  const v = window.__voice;
  if (!v) return;
  try { if (v.rec.state !== 'inactive') v.rec.stop(); } catch(e) {}
  try { v.stream.getTracks().forEach(t => t.stop()); } catch(e) {}
  window.__voice = null;
}
""",
)
external fun jsVoiceCancel()

// ---- blob URLs / download ----

@JsFun("(b64, mime) => { const bin = atob(b64); const u8 = new Uint8Array(bin.length); for (let i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i); return URL.createObjectURL(new Blob([u8], { type: mime || 'application/octet-stream' })); }")
external fun jsObjectUrl(b64: String, mime: String?): String

@JsFun("(url, name) => { const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); }")
external fun jsTriggerDownload(url: String, name: String)

@JsFun("(name) => { try { return new URLSearchParams(window.location.search).get(name); } catch(e) { return null; } }")
external fun jsQueryParam(name: String): String?

/**
 * Wire browser Back / edge-swipe to the in-app navigation stack.
 *
 * [onBack] is invoked for each popstate while the app has in-app depth; the
 * helper then re-arms a sentinel history entry so the NEXT gesture still has
 * something to consume (pushState does not fire popstate → no recursion).
 * [depth] reports the current in-app stack depth so the helper can push
 * matching entries when the app navigates forward itself.
 */
@JsFun(
    """
(depth, onBack) => {
  window.__agentNav = window.__agentNav || { depth: 0, installed: false };
  const st = window.__agentNav;
  st.depthFn = depth;
  window.__agentSyncNav = () => {
    const target = Math.max(0, st.depthFn() | 0);
    for (let i = st.depth; i < target; i++) window.history.pushState({ agentNav: i + 1 }, '');
    if (target > st.depth) st.depth = target;
  };
  if (st.installed) return;
  st.installed = true;
  window.history.replaceState({ agentNav: 0 }, '');
  window.addEventListener('popstate', () => {
    if (st.depthFn() > 0) {
      onBack();
      st.depth = Math.max(0, st.depthFn() | 0);
      window.history.pushState({ agentNav: st.depth + 1 }, '');
      st.depth += 1;
    }
  });
}
""",
)
external fun jsInstallHistoryNav(depth: () -> Int, onBack: () -> Unit)

/** Push history entries so Back has depth to consume (call when depth grows). */
@JsFun("() => { if (window.__agentSyncNav) window.__agentSyncNav(); }")
external fun jsSyncHistoryNav()

@JsFun("(s) => { const a = new TextEncoder().encode(s); let bin=''; const CH=0x8000; for (let i=0;i<a.length;i+=CH) bin+=String.fromCharCode.apply(null,a.subarray(i,i+CH)); return btoa(bin); }")
external fun jsUtf8ToB64(s: String): String

/** Async GET returning the body text; on failure reports (code, message). */
@JsFun(
    """
(url, onOk, onErr) => {
  fetch(url, { method: 'GET' }).then(resp => {
    if (!resp.ok) { onErr('http', 'HTTP ' + resp.status); return; }
    return resp.text().then(onOk);
  }).catch(e => onErr('unavailable', String(e)));
}
""",
)
external fun jsHttpGet(url: String, onOk: (String) -> Unit, onErr: (String, String) -> Unit)
