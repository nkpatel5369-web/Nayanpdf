import React, { useEffect, useState } from "react";
import { api } from "../api";
import { Check, ColorPicker, Field, Seg } from "./ui";

export type StampKind = "watermark" | "headerFooter" | "pageNumbers" | "bates" | "background";

export interface StampSpec {
  method: string;
  params: Record<string, any>;
}

const LABELS = ["CONFIDENTIAL", "DRAFT", "FOR REVIEW", "FINAL", "SAMPLE", "COPY", "PAID", "VERIFIED"];
const POSITIONS = ["top-left", "top-center", "top-right", "center", "bottom-left", "bottom-center", "bottom-right"];

export function StampForm(props: { onChange: (s: StampSpec | null) => void; initialKind?: StampKind }) {
  const [kind, setKind] = useState<StampKind>(props.initialKind || "watermark");
  // watermark
  const [wmType, setWmType] = useState<"text" | "image">("text");
  const [text, setText] = useState("CONFIDENTIAL");
  const [font, setFont] = useState("hebo");
  const [size, setSize] = useState(60);
  const [color, setColor] = useState("#c92a2a");
  const [opacity, setOpacity] = useState(0.2);
  const [angle, setAngle] = useState(45);
  const [position, setPosition] = useState("center");
  const [behind, setBehind] = useState(false);
  const [image, setImage] = useState("");
  const [scale, setScale] = useState(0.5);
  const [pages, setPages] = useState("");
  // header/footer
  const [hl, setHl] = useState("");
  const [hc, setHc] = useState("");
  const [hr, setHr] = useState("");
  const [fl, setFl] = useState("{name}");
  const [fc, setFc] = useState("");
  const [fr, setFr] = useState("Page {page} of {total}");
  const [hfSize, setHfSize] = useState(9);
  const [margin, setMargin] = useState(28);
  const [dateFormat, setDateFormat] = useState("%d/%m/%Y");
  const [skipFirst, setSkipFirst] = useState(false);
  // page numbers
  const [numPos, setNumPos] = useState("bottom-center");
  const [numFmt, setNumFmt] = useState("pageOf");
  const [start, setStart] = useState(1);
  const [custom, setCustom] = useState("");
  // bates
  const [prefix, setPrefix] = useState("");
  const [suffix, setSuffix] = useState("");
  const [digits, setDigits] = useState(6);
  const [batesStart, setBatesStart] = useState(1);
  const [batesPos, setBatesPos] = useState("bottom-right");
  // background/border
  const [bg, setBg] = useState(true);
  const [bgColor, setBgColor] = useState("#fff9db");
  const [border, setBorder] = useState(false);
  const [borderColor, setBorderColor] = useState("#000000");
  const [borderWidth, setBorderWidth] = useState(1);

  useEffect(() => {
    let spec: StampSpec | null = null;
    const pg = pages.trim() || undefined;
    if (kind === "watermark") {
      if (wmType === "text" && text.trim()) spec = { method: "stamp.watermark", params: { kind: "text", text, fontName: font, fontSize: size, color, opacity, angle, position, behind, pages: pg } };
      if (wmType === "image" && image) spec = { method: "stamp.watermark", params: { kind: "image", imagePath: image, opacity, position, scale, behind, pages: pg } };
    } else if (kind === "headerFooter") {
      if ([hl, hc, hr, fl, fc, fr].some((x) => x.trim()))
        spec = { method: "stamp.headerFooter", params: { header: { left: hl, center: hc, right: hr }, footer: { left: fl, center: fc, right: fr }, fontSize: hfSize, color, margin, dateFormat, skipFirst, pages: pg } };
    } else if (kind === "pageNumbers") {
      spec = { method: "stamp.pageNumbers", params: { position: numPos, numberFormat: numFmt, startNumber: start, custom: numFmt === "custom" ? custom || "{page}" : undefined, fontSize: hfSize + 1, color, margin, skipFirst, pages: pg } };
    } else if (kind === "bates") {
      spec = { method: "stamp.bates", params: { prefix, suffix, digits, startNumber: batesStart, position: batesPos, fontSize: hfSize, color, margin } };
    } else if (kind === "background") {
      if (bg || border) spec = { method: "stamp.background", params: { fill: bg, color: bgColor, opacity: 1, border, borderColor, borderWidth, pages: pg } };
    }
    props.onChange(spec);
  }, [kind, wmType, text, font, size, color, opacity, angle, position, behind, image, scale, pages, hl, hc, hr, fl, fc, fr, hfSize, margin, dateFormat, skipFirst, numPos, numFmt, start, custom, prefix, suffix, digits, batesStart, batesPos, bg, bgColor, border, borderColor, borderWidth]);

  return (
    <div>
      <Field label="Add">
        <Seg value={kind} onChange={setKind} options={[
          { value: "watermark", label: "Watermark" }, { value: "headerFooter", label: "Header & footer" },
          { value: "pageNumbers", label: "Page numbers" }, { value: "bates", label: "Bates numbers" }, { value: "background", label: "Background / border" },
        ]} />
      </Field>
      {kind === "watermark" && (
        <>
          <Seg value={wmType} onChange={setWmType} options={[{ value: "text", label: "Text" }, { value: "image", label: "Image" }]} />
          <div style={{ height: 10 }} />
          {wmType === "text" ? (
            <>
              <Field label="Text">
                <input className="input" value={text} onChange={(e) => setText(e.target.value)} />
                <div className="row wrap" style={{ gap: 4, marginTop: 6 }}>
                  {LABELS.map((l) => <button key={l} className={"btn sm" + (text === l ? " primary" : "")} onClick={() => setText(l)}>{l}</button>)}
                </div>
              </Field>
              <div className="grid3">
                <Field label="Font">
                  <select className="input" value={font} onChange={(e) => setFont(e.target.value)}>
                    <option value="hebo">Helvetica Bold</option><option value="helv">Helvetica</option><option value="tibo">Times Bold</option>
                    <option value="tiro">Times</option><option value="cobo">Courier Bold</option><option value="notosbo">Noto Sans Bold</option>
                  </select>
                </Field>
                <Field label="Size"><input className="input" type="number" value={size} onChange={(e) => setSize(parseFloat(e.target.value) || 60)} /></Field>
                <Field label="Rotation (°)"><input className="input" type="number" value={angle} onChange={(e) => setAngle(parseFloat(e.target.value) || 0)} /></Field>
              </div>
              <Field label="Colour"><ColorPicker value={color} onChange={setColor} /></Field>
            </>
          ) : (
            <Field label="Image">
              <div className="row">
                <input className="input" readOnly value={image} placeholder="Choose an image" />
                <button className="btn" onClick={async () => { const f = await api.openFiles("images"); if (f[0]) setImage(f[0]); }}>Browse...</button>
              </div>
              <div className="field" style={{ marginTop: 8 }}><label>Width: {Math.round(scale * 100)}% of page</label><input type="range" min={0.05} max={1} step={0.05} value={scale} onChange={(e) => setScale(parseFloat(e.target.value))} /></div>
            </Field>
          )}
          <div className="grid2">
            <Field label={`Opacity: ${Math.round(opacity * 100)}%`}><input type="range" min={0.05} max={1} step={0.05} value={opacity} onChange={(e) => setOpacity(parseFloat(e.target.value))} /></Field>
            <Field label="Position">
              <select className="input" value={position} onChange={(e) => setPosition(e.target.value)}>
                {POSITIONS.map((p) => <option key={p} value={p}>{p.replace("-", " ")}</option>)}
              </select>
            </Field>
          </div>
          <Check checked={behind} onChange={setBehind}>Place behind page content</Check>
        </>
      )}
      {kind === "headerFooter" && (
        <>
          <div className="label">Header (left / centre / right)</div>
          <div className="grid3"><input className="input" value={hl} onChange={(e) => setHl(e.target.value)} /><input className="input" value={hc} onChange={(e) => setHc(e.target.value)} /><input className="input" value={hr} onChange={(e) => setHr(e.target.value)} /></div>
          <div className="label" style={{ marginTop: 10 }}>Footer (left / centre / right)</div>
          <div className="grid3"><input className="input" value={fl} onChange={(e) => setFl(e.target.value)} /><input className="input" value={fc} onChange={(e) => setFc(e.target.value)} /><input className="input" value={fr} onChange={(e) => setFr(e.target.value)} /></div>
          <p className="subtle">Placeholders: {"{page} {total} {date} {name} {filename} {title}"}</p>
          <div className="grid3">
            <Field label="Font size"><input className="input" type="number" value={hfSize} onChange={(e) => setHfSize(parseFloat(e.target.value) || 9)} /></Field>
            <Field label="Margin (pt)"><input className="input" type="number" value={margin} onChange={(e) => setMargin(parseFloat(e.target.value) || 28)} /></Field>
            <Field label="Date format">
              <select className="input" value={dateFormat} onChange={(e) => setDateFormat(e.target.value)}>
                <option value="%d/%m/%Y">31/12/2026</option><option value="%d-%b-%Y">31-Dec-2026</option><option value="%d %B %Y">31 December 2026</option><option value="%Y-%m-%d">2026-12-31</option>
              </select>
            </Field>
          </div>
          <Field label="Colour"><ColorPicker value={color} onChange={setColor} /></Field>
          <Check checked={skipFirst} onChange={setSkipFirst}>Skip the first page</Check>
        </>
      )}
      {kind === "pageNumbers" && (
        <>
          <div className="grid2">
            <Field label="Position"><select className="input" value={numPos} onChange={(e) => setNumPos(e.target.value)}>{POSITIONS.filter((p) => p !== "center").map((p) => <option key={p} value={p}>{p.replace("-", " ")}</option>)}</select></Field>
            <Field label="Format">
              <select className="input" value={numFmt} onChange={(e) => setNumFmt(e.target.value)}>
                <option value="plain">1</option><option value="page">Page 1</option><option value="pageOf">Page 1 of N</option><option value="slash">1/N</option>
                <option value="dash">- 1 -</option><option value="roman">i, ii, iii</option><option value="ROMAN">I, II, III</option><option value="custom">Custom...</option>
              </select>
            </Field>
          </div>
          {numFmt === "custom" && <Field label="Custom text" hint="Use {page} and {total}"><input className="input" value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="Annexure A - {page}" /></Field>}
          <div className="grid3">
            <Field label="Start at"><input className="input" type="number" value={start} onChange={(e) => setStart(parseInt(e.target.value) || 1)} /></Field>
            <Field label="Font size"><input className="input" type="number" value={hfSize} onChange={(e) => setHfSize(parseFloat(e.target.value) || 9)} /></Field>
            <Field label="Margin (pt)"><input className="input" type="number" value={margin} onChange={(e) => setMargin(parseFloat(e.target.value) || 28)} /></Field>
          </div>
          <Check checked={skipFirst} onChange={setSkipFirst}>Skip the first page (cover)</Check>
        </>
      )}
      {kind === "bates" && (
        <>
          <div className="grid3">
            <Field label="Prefix"><input className="input" value={prefix} onChange={(e) => setPrefix(e.target.value)} placeholder="ACME-" /></Field>
            <Field label="Start number"><input className="input" type="number" value={batesStart} onChange={(e) => setBatesStart(parseInt(e.target.value) || 1)} /></Field>
            <Field label="Digits"><input className="input" type="number" min={1} max={12} value={digits} onChange={(e) => setDigits(parseInt(e.target.value) || 6)} /></Field>
          </div>
          <div className="grid2">
            <Field label="Suffix"><input className="input" value={suffix} onChange={(e) => setSuffix(e.target.value)} /></Field>
            <Field label="Position"><select className="input" value={batesPos} onChange={(e) => setBatesPos(e.target.value)}>{POSITIONS.filter((p) => p !== "center").map((p) => <option key={p} value={p}>{p.replace("-", " ")}</option>)}</select></Field>
          </div>
          <p className="subtle">Example: {prefix}{String(batesStart).padStart(digits, "0")}{suffix}</p>
        </>
      )}
      {kind === "background" && (
        <>
          <Check checked={bg} onChange={setBg}>Background colour</Check>
          {bg && <ColorPicker value={bgColor} onChange={setBgColor} colors={["#fff9db", "#e7f5ff", "#ebfbee", "#f8f0fc", "#f1f3f5", "#ffffff"]} />}
          <Check checked={border} onChange={setBorder}>Page border</Check>
          {border && (
            <div className="row"><ColorPicker value={borderColor} onChange={setBorderColor} /><input className="input" type="number" style={{ width: 80 }} value={borderWidth} onChange={(e) => setBorderWidth(parseFloat(e.target.value) || 1)} /> pt</div>
          )}
        </>
      )}
      {kind !== "bates" && (
        <Field label="Pages" hint="Leave empty for all pages. Examples: 1-3, 5, 8-, odd, even">
          <input className="input" value={pages} onChange={(e) => setPages(e.target.value)} placeholder="All pages" />
        </Field>
      )}
    </div>
  );
}
