import React, { useEffect, useRef, useState } from "react";
import { BadgeCheck, FileKey2, ImagePlus, PenTool, ShieldAlert, ShieldCheck, Trash2, Type } from "lucide-react";
import { useWs } from "../context";
import { Check, Field, Modal, Notice, Seg } from "../../components/ui";
import { api, baseName, stem, dirName, joinPath } from "../../api";
import { useApp } from "../../store";
import { openDocument } from "../../components/common";

export function SignPanel() {
  const ws = useWs();
  const [sigs, setSigs] = useState<any[]>([]);
  const [create, setCreate] = useState(false);
  const [verify, setVerify] = useState<any>(null);
  const [digital, setDigital] = useState<{ page: number; rect?: number[] } | null>(null);
  const reload = () => api.signatures().then(setSigs).catch(() => undefined);
  useEffect(() => {
    reload();
    if (!ws.tool || ws.tool === "none") ws.setTool("placeSignature");
    const h = (e: any) => {
      if (e.detail.sessionId === ws.sessionId) setDigital({ page: e.detail.page, rect: e.detail.rect });
    };
    window.addEventListener("nayan:digital-box", h);
    return () => window.removeEventListener("nayan:digital-box", h);
  }, []);
  useEffect(() => {
    ws.query("sign.verify", {}).then(setVerify).catch(() => setVerify(null));
  }, [ws.revision]);
  const selected = sigs.find((s) => ws.opts.signatureId === s.id);
  return (
    <div className="side-body panel-pad">
      <h3>Signature image</h3>
      <p className="subtle" style={{ marginTop: 4 }}>A picture of your signature. It shows intent but does not cryptographically protect the document.</p>
      <div className="sig-list">
        {sigs.map((s) => (
          <div key={s.id} className={"sig-item" + (ws.opts.signatureId === s.id ? " on" : "")} title={s.name}
            onClick={() => { ws.setOpts({ signatureId: s.id, signaturePng: s.png }); ws.setTool("placeSignature"); }}>
            <img src={"data:image/png;base64," + s.png} />
            <button className="icon-btn" style={{ position: "absolute", right: 0, top: 0, width: 22, height: 22 }} title="Delete" onClick={(e) => { e.stopPropagation(); api.deleteSignature(s.id).then(setSigs); }}>
              <Trash2 size={12} />
            </button>
          </div>
        ))}
        <button className="sig-item" style={{ background: "var(--surface-2)" }} onClick={() => setCreate(true)}>+ New</button>
      </div>
      {selected && (
        <>
          <Field label="Place on">
            <Seg value={ws.opts.signPagesMode || "this"} onChange={(v) => ws.setOpts({ signPagesMode: v, signPages: v === "all" ? "all" : v === "this" ? undefined : ws.opts.signPages })}
              options={[{ value: "this", label: "This page" }, { value: "all", label: "All pages" }, { value: "range", label: "Pages..." }]} />
          </Field>
          {ws.opts.signPagesMode === "range" && <input className="input" placeholder="e.g. 1, 3-5, last" value={ws.opts.signPages || ""} onChange={(e) => ws.setOpts({ signPages: e.target.value })} />}
          <Check checked={!!ws.opts.signDate} onChange={(v) => ws.setOpts({ signDate: v })}>Add date below signature</Check>
          <Check checked={ws.opts.signFlatten !== false} onChange={(v) => ws.setOpts({ signFlatten: v })}>Embed in page content (not a movable annotation)</Check>
          <p className="subtle">Now drag a box on the page (or click) to place the signature.</p>
        </>
      )}
      <div className="divider" />
      <h3>Digital signature (certificate)</h3>
      <p className="subtle" style={{ marginTop: 4 }}>Cryptographically signs the document with your certificate (.pfx file or a USB DSC token). Any later change is detectable.</p>
      <div className="row">
        <button className={"btn" + (ws.tool === "digitalBox" ? " primary" : "")} onClick={() => ws.setTool("digitalBox")}><FileKey2 size={15} /> Draw signature box</button>
        <button className="btn" onClick={() => setDigital({ page: ws.page })}>Invisible</button>
      </div>
      <div className="divider" />
      <h3>Signatures in this document</h3>
      {!verify && <div className="subtle">Checking...</div>}
      {verify?.damaged && <Notice kind="err">{verify.error}</Notice>}
      {verify && !verify.damaged && verify.count === 0 && <div className="subtle">No digital signatures.</div>}
      {verify?.signatures?.map((s: any) => <SigStatus key={s.field} s={s} />)}
      {verify?.count > 0 && <p className="subtle">{verify.note}</p>}
      {create && <SignatureCreator onClose={() => setCreate(false)} onSaved={(s) => { reload(); ws.setOpts({ signatureId: s.id, signaturePng: s.png }); ws.setTool("placeSignature"); setCreate(false); }} />}
      {digital && <DigitalSignDialog page={digital.page} rect={digital.rect} onClose={() => setDigital(null)} />}
    </div>
  );
}

function SigStatus({ s }: { s: any }) {
  const ok = s.intact && s.valid;
  return (
    <div className="card" style={{ padding: 10, marginTop: 8 }}>
      <div className="row">
        {ok ? <ShieldCheck size={17} color="var(--success)" /> : <ShieldAlert size={17} color="var(--danger)" />}
        <strong className="grow ellipsis">{s.signer || s.field}</strong>
      </div>
      <div className="subtle" style={{ marginTop: 4 }}>
        {ok ? "Signature valid; document unchanged since signing" : s.error || "INVALID: the document was changed after signing or the signature is corrupt"}
        {s.coverage && s.coverage !== "ENTIRE_FILE" && ok ? " (later revisions were added after this signature)" : ""}
      </div>
      <div className="row wrap" style={{ gap: 4, marginTop: 6 }}>
        <span className={"badge " + (s.trusted ? "ok" : "warn")}>{s.trusted ? "Trusted certificate" : "Certificate not verified as trusted"}</span>
        {s.selfSigned && <span className="badge warn">Self-signed</span>}
        <span className="badge">Revocation not checked (offline)</span>
      </div>
      {s.signedAt && <div className="subtle" style={{ marginTop: 4 }}>Signed {new Date(s.signedAt).toLocaleString()}</div>}
    </div>
  );
}

export function SignatureCreator(props: { onClose: () => void; onSaved: (s: any) => void }) {
  const [tab, setTab] = useState<"draw" | "type" | "image">("draw");
  const [name, setName] = useState(useApp.getState().settings.reviewerName || "");
  const [kind, setKind] = useState<"signature" | "initials">("signature");
  const [png, setPng] = useState<string | null>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);
  const [color, setColor] = useState("#1a237e");

  useEffect(() => {
    if (tab !== "draw") return;
    const c = canvas.current!;
    const ctx = c.getContext("2d")!;
    ctx.clearRect(0, 0, c.width, c.height);
    setPng(null);
  }, [tab]);

  const pos = (e: React.PointerEvent) => {
    const r = canvas.current!.getBoundingClientRect();
    return [((e.clientX - r.left) / r.width) * canvas.current!.width, ((e.clientY - r.top) / r.height) * canvas.current!.height];
  };
  const down = (e: React.PointerEvent) => {
    drawing.current = true;
    const ctx = canvas.current!.getContext("2d")!;
    const [x, y] = pos(e);
    ctx.strokeStyle = color;
    ctx.lineWidth = 4;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    ctx.moveTo(x, y);
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  };
  const move = (e: React.PointerEvent) => {
    if (!drawing.current) return;
    const ctx = canvas.current!.getContext("2d")!;
    const [x, y] = pos(e);
    ctx.lineTo(x, y);
    ctx.stroke();
  };
  const up = () => {
    drawing.current = false;
    setPng(trimCanvas(canvas.current!));
  };
  const typed = async (text: string) => {
    if (!text.trim()) return setPng(null);
    const r = await api.query("sign.typedImage", { text, color });
    setPng(r.png);
  };
  const importImg = async () => {
    const f = await api.openFiles("images", false, "Choose signature image");
    if (!f[0]) return;
    const r = await api.query("sign.cleanImage", { imagePath: f[0] });
    setPng(r.png);
  };
  return (
    <Modal title="New signature" onClose={props.onClose} footer={
      <>
        <button className="btn" onClick={props.onClose}>Cancel</button>
        <button className="btn primary" disabled={!png} onClick={async () => props.onSaved(await api.saveSignature(name || (kind === "initials" ? "Initials" : "Signature"), kind, png!))}>Save</button>
      </>
    }>
      <div className="row" style={{ marginBottom: 12 }}>
        <Seg value={tab} onChange={setTab} options={[{ value: "draw", label: <><PenTool size={14} /> Draw</> }, { value: "type", label: <><Type size={14} /> Type</> }, { value: "image", label: <><ImagePlus size={14} /> Image</> }]} />
        <div className="grow" />
        <Seg value={kind} onChange={setKind} options={[{ value: "signature", label: "Signature" }, { value: "initials", label: "Initials" }]} />
      </div>
      {tab === "draw" && (
        <>
          <canvas ref={canvas} width={1040} height={300} className="sig-pad" style={{ width: "100%", height: 170 }} onPointerDown={down} onPointerMove={move} onPointerUp={up} />
          <div className="row" style={{ marginTop: 6 }}>
            <input type="color" value={color} onChange={(e) => setColor(e.target.value)} />
            <button className="btn sm" onClick={() => { const c = canvas.current!; c.getContext("2d")!.clearRect(0, 0, c.width, c.height); setPng(null); }}>Clear</button>
          </div>
        </>
      )}
      {tab === "type" && (
        <>
          <input className="input" placeholder="Type your name" defaultValue={name} onChange={(e) => { setName(e.target.value); typed(e.target.value); }} />
          <div className="row" style={{ marginTop: 6 }}><input type="color" value={color} onChange={(e) => setColor(e.target.value)} /></div>
          {png && <div className="sig-item" style={{ height: 110, marginTop: 10 }}><img src={"data:image/png;base64," + png} style={{ maxHeight: 96 }} /></div>}
        </>
      )}
      {tab === "image" && (
        <>
          <button className="btn" onClick={importImg}><ImagePlus size={15} /> Choose image...</button>
          <p className="subtle">A photo or scan of your signature on white paper. The light background is made transparent.</p>
          {png && <div className="sig-item" style={{ height: 110 }}><img src={"data:image/png;base64," + png} style={{ maxHeight: 96 }} /></div>}
        </>
      )}
      <Field label="Name for this signature"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
      <p className="subtle">Saved signatures are stored only on this computer{" "}(encrypted with your Windows account).</p>
    </Modal>
  );
}

function trimCanvas(c: HTMLCanvasElement): string | null {
  const ctx = c.getContext("2d")!;
  const { width, height } = c;
  const data = ctx.getImageData(0, 0, width, height).data;
  let x0 = width, y0 = height, x1 = 0, y1 = 0;
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    if (data[(y * width + x) * 4 + 3] > 10) {
      if (x < x0) x0 = x;
      if (y < y0) y0 = y;
      if (x > x1) x1 = x;
      if (y > y1) y1 = y;
    }
  }
  if (x1 <= x0 || y1 <= y0) return null;
  const pad = 8;
  const w = x1 - x0 + pad * 2;
  const h = y1 - y0 + pad * 2;
  const o = document.createElement("canvas");
  o.width = w;
  o.height = h;
  o.getContext("2d")!.drawImage(c, x0 - pad, y0 - pad, w, h, 0, 0, w, h);
  return o.toDataURL("image/png").split(",")[1];
}

export function DigitalSignDialog(props: { page: number; rect?: number[]; onClose: () => void }) {
  const ws = useWs();
  const [source, setSource] = useState<"pfx" | "pkcs11">("pfx");
  const [pfx, setPfx] = useState("");
  const [pfxPw, setPfxPw] = useState("");
  const [certInfo, setCertInfo] = useState<any>(null);
  const [lib, setLib] = useState("");
  const [pin, setPin] = useState("");
  const [tokens, setTokens] = useState<any[] | null>(null);
  const [cert, setCert] = useState<{ token: string; id: string; label: string } | null>(null);
  const [reason, setReason] = useState("I approve this document");
  const [location, setLocation] = useState("");
  const [certify, setCertify] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [newCert, setNewCert] = useState(false);
  const checkPfx = async () => {
    setErr(null);
    try {
      setCertInfo(await api.query("sign.certificateInfo", { pfxPath: pfx, password: pfxPw }));
    } catch (e: any) {
      setErr(e.message);
      setCertInfo(null);
    }
  };
  const listTokens = async () => {
    setErr(null);
    try {
      const r = await api.query("sign.pkcs11List", { libPath: lib, pin });
      setTokens(r.tokens);
    } catch (e: any) {
      setErr(e.message);
    }
  };
  const sign = async () => {
    const st = ws.doc.state;
    const base = st.savedPath || st.originalPath;
    const out = await api.saveFile(joinPath(dirName(base), `${stem(base)}_signed.pdf`), "pdf", "Save signed PDF as");
    if (!out) return;
    setBusy(true);
    setErr(null);
    const params: any = { page: props.page, rect: props.rect, invisible: !props.rect, reason, location, certify, output: out, source };
    if (source === "pfx") Object.assign(params, { pfxPath: pfx, pfxPassword: pfxPw });
    else Object.assign(params, { pkcs11Lib: lib, pin, tokenLabel: cert?.token, certId: cert?.id, certLabel: cert?.label });
    const sig = ws.opts.signaturePng;
    if (sig && props.rect) params.signatureImage = sig;
    try {
      const j = await api.startJob("sign.digital", { ...params, sessionId: ws.sessionId }, "Digital signature");
      const { waitForJob } = await import("../../api");
      const done = await waitForJob(j.id);
      if (done.status === "succeeded") {
        useApp.getState().toast({ kind: "success", title: "Document signed", body: `Signature verified. Saved as ${baseName(out)}`, actions: [{ label: "Open signed PDF", run: () => openDocument(done.result.output) }] });
        props.onClose();
      } else {
        setErr(done.error?.message || "Signing failed");
      }
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setBusy(false);
    }
  };
  const ready = source === "pfx" ? !!(pfx && pfxPw && certInfo) : !!(lib && pin && cert);
  return (
    <Modal title="Digital signature" onClose={props.onClose} wide footer={
      <>
        <button className="btn" onClick={props.onClose}>Cancel</button>
        <button className="btn primary" disabled={!ready || busy} onClick={sign}><BadgeCheck size={15} /> {busy ? "Signing..." : "Sign and save"}</button>
      </>
    }>
      <Notice kind="info">
        {props.rect ? `A visible signature is placed on page ${props.page}.` : "An invisible signature is added (shown in the signature panel of PDF readers)."} The signed copy is saved as a new file; your current document is unchanged. Sign as the last step: any later edit will be detectable.
      </Notice>
      {ws.doc.state.dirty && <Notice kind="warn">Your unsaved edits are included in the signed copy.</Notice>}
      <Field label="Certificate source">
        <Seg value={source} onChange={setSource} options={[{ value: "pfx", label: "Certificate file (.pfx / .p12)" }, { value: "pkcs11", label: "USB token / smart card (PKCS#11)" }]} />
      </Field>
      {source === "pfx" ? (
        <>
          <div className="row">
            <input className="input" readOnly value={pfx} placeholder="No certificate selected" />
            <button className="btn" onClick={async () => { const f = await api.openFiles("pfx"); if (f[0]) { setPfx(f[0]); setCertInfo(null); } }}>Browse...</button>
            <button className="btn" onClick={() => setNewCert(true)}>Create...</button>
          </div>
          <div className="row" style={{ marginTop: 8 }}>
            <input className="input" type="password" placeholder="Certificate password" value={pfxPw} onChange={(e) => { setPfxPw(e.target.value); setCertInfo(null); }} />
            <button className="btn" disabled={!pfx || !pfxPw} onClick={checkPfx}>Check</button>
          </div>
          {certInfo && (
            <Notice kind={certInfo.expired ? "err" : certInfo.selfSigned ? "warn" : "ok"}>
              <div><strong>{certInfo.subject}</strong></div>
              <div className="subtle">Issued by {certInfo.issuer} · valid until {new Date(certInfo.notAfter).toLocaleDateString()}</div>
              {certInfo.selfSigned && <div className="subtle">Self-signed: recipients will see the signature as valid but the signer as not trusted unless they trust this certificate.</div>}
              {certInfo.expired && <div>This certificate has expired.</div>}
            </Notice>
          )}
        </>
      ) : (
        <>
          <Field label="Token driver (PKCS#11 DLL from your token vendor, e.g. eps2003csp11.dll)">
            <div className="row">
              <input className="input" readOnly value={lib} />
              <button className="btn" onClick={async () => { const f = await api.openFiles("dll"); if (f[0]) setLib(f[0]); }}>Browse...</button>
            </div>
          </Field>
          <div className="row">
            <input className="input" type="password" placeholder="Token PIN" value={pin} onChange={(e) => setPin(e.target.value)} />
            <button className="btn" disabled={!lib || !pin} onClick={listTokens}>Read token</button>
          </div>
          {tokens && tokens.length === 0 && <Notice kind="warn">No token detected. Check that it is plugged in.</Notice>}
          {tokens?.map((t) => (
            <div key={t.slot} style={{ marginTop: 8 }}>
              <div className="label">{t.label} ({t.manufacturer})</div>
              {t.error && <Notice kind="err">{t.error}</Notice>}
              {t.certificates.map((c: any) => (
                <label key={c.id} className="check"><input type="radio" checked={cert?.id === c.id} onChange={() => setCert({ token: t.label, id: c.id, label: c.label })} /> {c.subject} <span className="subtle">(valid to {c.notAfter?.slice(0, 10)})</span></label>
              ))}
            </div>
          ))}
          <p className="subtle">PKCS#11 signing follows the token's standard interface. It has been tested with a software token; behaviour with specific USB token models has not been verified.</p>
        </>
      )}
      <div className="grid2">
        <Field label="Reason"><input className="input" value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <Field label="Location"><input className="input" value={location} onChange={(e) => setLocation(e.target.value)} placeholder="e.g. Mumbai" /></Field>
      </div>
      <Check checked={certify} onChange={setCertify}>Certify (author signature: only form filling allowed afterwards)</Check>
      {err && <Notice kind="err">{err}</Notice>}
      {newCert && <CreateCertDialog onClose={() => setNewCert(false)} onCreated={(p, pw) => { setPfx(p); setPfxPw(pw); setNewCert(false); }} />}
    </Modal>
  );
}

export function CreateCertDialog(props: { onClose: () => void; onCreated: (path: string, pw: string) => void }) {
  const [cn, setCn] = useState(useApp.getState().settings.reviewerName || "");
  const [org, setOrg] = useState("");
  const [email, setEmail] = useState("");
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const create = async () => {
    const out = await api.saveFile(`${cn || "my-signature"}.pfx`, "pfx", "Save certificate");
    if (!out) return;
    try {
      const j = await api.startJob("sign.createCertificate", { output: out, password: pw, commonName: cn, organization: org, email }, "Create certificate");
      const { waitForJob } = await import("../../api");
      const done = await waitForJob(j.id);
      if (done.status === "succeeded") props.onCreated(done.result.output, pw);
      else setErr(done.error?.message || "Failed");
    } catch (e: any) {
      setErr(e.message);
    }
  };
  return (
    <Modal title="Create a personal signing certificate" onClose={props.onClose} footer={
      <><button className="btn" onClick={props.onClose}>Cancel</button><button className="btn primary" disabled={!cn || pw.length < 6 || pw !== pw2} onClick={create}>Create</button></>
    }>
      <Notice kind="warn">A self-signed certificate protects documents against undetected changes, but it is not issued by a certifying authority. For filings that require a Class 3 DSC, use your token instead.</Notice>
      <Field label="Your name"><input className="input" value={cn} onChange={(e) => setCn(e.target.value)} /></Field>
      <div className="grid2">
        <Field label="Organisation (optional)"><input className="input" value={org} onChange={(e) => setOrg(e.target.value)} /></Field>
        <Field label="Email (optional)"><input className="input" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
      </div>
      <div className="grid2">
        <Field label="Password (min 6)"><input className="input" type="password" value={pw} onChange={(e) => setPw(e.target.value)} /></Field>
        <Field label="Repeat password"><input className="input" type="password" value={pw2} onChange={(e) => setPw2(e.target.value)} /></Field>
      </div>
      {err && <Notice kind="err">{err}</Notice>}
      <p className="subtle">Keep the .pfx file and its password safe; anyone with both can sign as you.</p>
    </Modal>
  );
}
