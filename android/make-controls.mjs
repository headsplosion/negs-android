// Writes the app's control list from the web app's, so both offer the same
// settings: `node android/make-controls.mjs`.
import { readFileSync, writeFileSync } from "node:fs";
const source = readFileSync(new URL("../web/controls.js", import.meta.url), "utf8");
const { PREP_SECTIONS, SECTIONS, OUTPUT_SECTIONS, TITLES } = await import("data:text/javascript," + encodeURIComponent(source));
const out = new URL("app/src/main/assets/controls.json", import.meta.url);
writeFileSync(out, JSON.stringify({ prep: PREP_SECTIONS, lab: SECTIONS, output: OUTPUT_SECTIONS, titles: TITLES }, null, 1) + "\n");
