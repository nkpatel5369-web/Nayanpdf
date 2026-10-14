// Scripted dialog answers for automated UI tests. Only active in unpackaged
// development builds when NAYANPDF_TEST_DIALOGS points to a JSON file; the
// installed application never reads it.
import { app } from "electron";
import { existsSync, readFileSync } from "node:fs";

export function testAnswer(key: string): { hit: boolean; value?: any } {
  const f = process.env.NAYANPDF_TEST_DIALOGS;
  if (app.isPackaged || !f || !existsSync(f)) return { hit: false };
  try {
    const j = JSON.parse(readFileSync(f, "utf8"));
    if (key in j) {
      process.stdout.write(`[testhook] ${key} -> ${JSON.stringify(j[key])}\n`);
      return { hit: true, value: j[key] };
    }
  } catch (e) {
    process.stdout.write(`[testhook] ${key} unreadable: ${e}\n`);
  }
  process.stdout.write(`[testhook] ${key} -> no answer\n`);
  return { hit: false };
}
