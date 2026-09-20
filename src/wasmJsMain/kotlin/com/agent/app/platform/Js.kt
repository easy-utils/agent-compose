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

// ---- voice recording (Web Audio -> raw Float32 PCM) ----
//
// MediaRecorder is deliberately NOT used: it only yields WebM/Opus (Chrome) or
// MP4/AAC (Safari), and the ASR gateway rejects WebM outright (a WebM container
// even sniffs as `video/webm`). Instead we capture mono PCM with an AudioWorklet
// (ScriptProcessor fallback) at 16 kHz and hand the raw Float32 frames back to
// Kotlin, which encodes the WAV — matching Flutter's recorder.

@JsFun(
    """
(cb) => {
  if (!navigator.mediaDevices) { cb(false); return; }
  const Ctor = window.AudioContext || window.webkitAudioContext;
  if (!Ctor) { cb(false); return; }
  navigator.mediaDevices.getUserMedia({ audio: true }).then(async s => {
    try {
      const ctx = new Ctor({ sampleRate: 16000 });
      if (ctx.state === 'suspended') await ctx.resume();
      const source = ctx.createMediaStreamSource(s);
      const frames = [];
      const v = { stream: s, ctx: ctx, source: source, frames: frames, worklet: null, processor: null };
      window.__voice = v;
      try {
        if (!ctx.audioWorklet) throw new Error('no AudioWorklet');
        const src = "class AbcpRecProcessor extends AudioWorkletProcessor { process(inputs){ const ch = inputs[0] && inputs[0][0]; if (ch) this.port.postMessage(ch.slice(0)); return true; } } registerProcessor('abcp-rec', AbcpRecProcessor);";
        const url = URL.createObjectURL(new Blob([src], { type: 'application/javascript' }));
        await ctx.audioWorklet.addModule(url);
        const node = new AudioWorkletNode(ctx, 'abcp-rec');
        node.port.onmessage = e => { if (window.__voice === v) frames.push(e.data); };
        source.connect(node);
        const mute = ctx.createGain(); mute.gain.value = 0;
        node.connect(mute).connect(ctx.destination);
        v.worklet = node;
      } catch (err) {
        const node = ctx.createScriptProcessor(4096, 1, 1);
        node.onaudioprocess = ev => { if (window.__voice === v) frames.push(new Float32Array(ev.inputBuffer.getChannelData(0))); };
        const mute = ctx.createGain(); mute.gain.value = 0;
        source.connect(node); node.connect(mute).connect(ctx.destination);
        v.processor = node;
      }
      cb(true);
    } catch (err) { cb(false); }
  }).catch(e => cb(false));
}
""",
)
external fun jsVoiceStart(cb: (Boolean) -> Unit)

/** Stop and return the captured mono Float32 PCM, base64-encoded (LE bytes). */
@JsFun(
    """
(cb) => {
  const v = window.__voice;
  if (!v) { cb(null); return; }
  window.__voice = null;
  try { v.worklet && v.worklet.port.close(); } catch(e) {}
  try { v.processor && v.processor.disconnect(); } catch(e) {}
  try { v.worklet && v.worklet.disconnect(); } catch(e) {}
  try { v.source && v.source.disconnect(); } catch(e) {}
  try { v.stream.getTracks().forEach(t => t.stop()); } catch(e) {}
  try { v.ctx.close(); } catch(e) {}
  const frames = v.frames;
  let n = 0; for (const f of frames) n += f.length;
  const pcm = new Float32Array(n);
  let o = 0; for (const f of frames) { pcm.set(f, o); o += f.length; }
  const u8 = new Uint8Array(pcm.buffer);
  let bin = ''; const CH = 0x8000;
  for (let k = 0; k < u8.length; k += CH) bin += String.fromCharCode.apply(null, u8.subarray(k, k + CH));
  cb(btoa(bin));
}
""",
)
external fun jsVoiceStop(cb: (String?) -> Unit)

@JsFun(
    """
() => {
  const v = window.__voice;
  if (!v) return;
  window.__voice = null;
  try { v.worklet && v.worklet.port.close(); } catch(e) {}
  try { v.processor && v.processor.disconnect(); } catch(e) {}
  try { v.worklet && v.worklet.disconnect(); } catch(e) {}
  try { v.source && v.source.disconnect(); } catch(e) {}
  try { v.stream.getTracks().forEach(t => t.stop()); } catch(e) {}
  try { v.ctx.close(); } catch(e) {}
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
