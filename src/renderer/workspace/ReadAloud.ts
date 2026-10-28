// Read Out Loud (Acrobat: View > Read Out Loud). Uses the speech voices installed
// in Windows through the browser engine's speech synthesis; nothing is sent over
// the network. Text comes from the page's text layer, so scanned pages need OCR.

import { useApp } from "../store";

export interface ReadState { page: number; toEnd: boolean; paused: boolean }

let session = 0;

/** Voices installed on this computer (may take a moment to appear after start-up). */
export function speechVoices(timeoutMs = 1500): Promise<SpeechSynthesisVoice[]> {
  const synth = window.speechSynthesis;
  if (!synth) return Promise.resolve([]);
  const now = synth.getVoices();
  if (now.length) return Promise.resolve(now);
  return new Promise((resolve) => {
    const done = () => {
      synth.removeEventListener("voiceschanged", done);
      clearTimeout(t);
      resolve(synth.getVoices());
    };
    const t = setTimeout(done, timeoutMs);
    synth.addEventListener("voiceschanged", done);
  });
}

async function pageText(pdf: any, page: number): Promise<string> {
  const p = await pdf.getPage(page);
  const tc = await p.getTextContent();
  return tc.items.map((i: any) => i.str + (i.hasEOL ? "\n" : " ")).join("").replace(/[ \t]+/g, " ").trim();
}

/**
 * Read one page, or from a page to the end of the document. Calls onState with the
 * page being read, and with null when reading stops.
 */
export async function readAloud(pdf: any, start: number, toEnd: boolean, goTo: (p: number) => void, onState: (s: ReadState | null) => void) {
  const synth = window.speechSynthesis;
  const toast = useApp.getState().toast;
  const voices = await speechVoices();
  if (!synth || !voices.length) {
    toast({ kind: "warning", title: "No speech voices are installed", body: "Windows: Settings > Time & language > Speech lists the installed voices. NayanPDF uses them offline; it never downloads voices." });
    onState(null);
    return;
  }
  const s = useApp.getState().settings;
  const voice = voices.find((v) => v.name === s.readVoice) || voices.find((v) => v.default) || voices[0];
  const id = ++session;
  synth.cancel();
  let page = start;
  const count = pdf.numPages;
  const next = async () => {
    if (id !== session) return;
    const text = await pageText(pdf, page);
    if (id !== session) return;
    goTo(page);
    onState({ page, toEnd, paused: false });
    if (!text) {
      if (!toEnd) {
        toast({ kind: "info", title: `Page ${page} has no text to read`, body: "Scanned pages need OCR (Recognize text) first." });
        onState(null);
        return;
      }
      if (page < count) {
        page++;
        return next();
      }
      onState(null);
      return;
    }
    // Long pages are split at sentence ends so the speech engine does not cut them off.
    const chunks = text.match(/[^.!?\n]{1,280}([.!?]+|\n|$)/g)?.map((c) => c.trim()).filter(Boolean) || [text];
    chunks.forEach((chunk, i) => {
      const u = new SpeechSynthesisUtterance(chunk);
      u.voice = voice;
      u.lang = voice.lang;
      u.rate = s.readRate || 1;
      if (i === chunks.length - 1) {
        u.onend = () => {
          if (id !== session) return;
          if (toEnd && page < count) {
            page++;
            next();
          } else onState(null);
        };
      }
      u.onerror = (e: any) => {
        if (id === session && e?.error !== "interrupted" && e?.error !== "canceled") {
          toast({ kind: "error", ...speechError(String(e?.error || "")) });
          stopReading();
          onState(null);
        }
      };
      synth.speak(u);
    });
  };
  await next();
}

/** Plain-language message for a speech engine error code. */
function speechError(code: string): { title: string; body: string } {
  if (["synthesis-failed", "audio-hardware", "audio-busy", "synthesis-unavailable"].includes(code)) {
    return {
      title: "Windows could not play the speech",
      body: "Check that speakers or headphones are connected and not muted, then try again. The voice can be tested in Windows Settings > Time & language > Speech.",
    };
  }
  if (code === "voice-unavailable" || code === "language-unavailable") {
    return { title: "The chosen voice is not available", body: "Choose another voice in Settings > Read Out Loud." };
  }
  return { title: "Read Out Loud stopped", body: code ? `The speech engine reported: ${code}` : "The speech engine stopped." };
}

export function pauseResume(): boolean | null {
  const synth = window.speechSynthesis;
  if (!synth || (!synth.speaking && !synth.paused)) return null;
  if (synth.paused) {
    synth.resume();
    return false;
  }
  synth.pause();
  return true;
}

export function stopReading() {
  session++;
  window.speechSynthesis?.cancel();
}
