import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const sourcePath = new URL("../src/features/platform/workspaces/WorkspaceManager.tsx", import.meta.url);
const source = readFileSync(sourcePath, "utf8");
const match = source.match(/\bpattern=(?:"([^"]*)"|\{("(?:\\.|[^"\\])*")\})/);
const workspaceKeyPattern = match?.[1] ?? (match?.[2] ? JSON.parse(match[2]) : undefined);
assert.ok(workspaceKeyPattern, "workspace creation input must define a native validation pattern");
const pattern = new RegExp(`^(?:${workspaceKeyPattern})$`, "v");

for (const valid of ["abc", "a-b", "a--", "space9"]) assert.equal(pattern.test(valid), true, `${valid} should be valid`);
for (const invalid of ["Aaa", "1ab", "ab", "a".repeat(65)]) assert.equal(pattern.test(invalid), false, `${invalid} should be invalid`);

console.log("Workspace key pattern compiles in Unicode Sets mode and matches the server contract.");
