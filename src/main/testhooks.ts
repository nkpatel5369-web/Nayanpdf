// Scripted dialog answers for automated UI tests. Only active in unpackaged
// development builds when NAYANPDF_TEST_DIALOGS points to a JSON file; the
// installed application never reads it.
import { app } from "electron";
import { existsSync, readFileSync } from "node:fs";

export function testAnswer(key: string, suggested?: string): { hit: boolean; value?: any } {
  const f = process.env.NAYANPDF_TEST_DIALOGS;
  if (app.isPackaged || !f || !existsSync(f)) return { hit: false };
  // What the real dialog would have offered, so tests can check it.
  const label = suggested === undefined ? key : `${key} suggested=${JSON.stringify(suggested)}`;
  try {
    const j = JSON.parse(readFileSync(f, "utf8"));
    if (key in j) {
      process.stdout.write(`[testhook] ${label} -> ${JSON.stringify(j[key])}\n`);
      return { hit: true, value: j[key] };
    }
  } catch (e) {
    process.stdout.write(`[testhook] ${label} unreadable: ${e}\n`);
  }
  process.stdout.write(`[testhook] ${label} -> no answer\n`);
  return { hit: false };
}
