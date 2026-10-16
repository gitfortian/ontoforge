// Independent Node.js / ECMAScript RFC 8785 oracle for shared Java JCS vectors.
// No external packages. Canonical object keys use UTF-16 lexical order; primitives
// use ECMAScript JSON.stringify number/string serialization (RFC 8785 section 3).
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

function checkUnicode(value) {
  if (typeof value === 'string') {
    // /u matches lone surrogates but not a valid surrogate pair as two code points.
    if (/[\uD800-\uDFFF]/u.test(value)) throw new Error('Unpaired surrogate rejected');
  } else if (Array.isArray(value)) {
    value.forEach(checkUnicode);
  } else if (value !== null && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) {
      checkUnicode(key);
      checkUnicode(item);
    }
  }
}

function canonical(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  return '{' + Object.keys(value).sort()
    .map(key => JSON.stringify(key) + ':' + canonical(value[key]))
    .join(',') + '}';
}

const filename = new URL('../../doc/design/fixtures/ofip-0003-jcs-fixtures.json', import.meta.url);
const suite = JSON.parse(readFileSync(filename, 'utf8'));
if (suite.profile !== 'jcs-rfc8785-v1' || !Array.isArray(suite.vectors) || suite.vectors.length < 4)
  throw new Error('Invalid JCS fixture suite');
for (const vector of suite.vectors) {
  const value = JSON.parse(vector.raw);
  checkUnicode(value);
  const actual = canonical(value);
  const sha256 = createHash('sha256').update(Buffer.from(actual, 'utf8')).digest('hex');
  if (actual !== vector.canonical || sha256 !== vector.sha256)
    throw new Error(`JCS cross-language mismatch in ${vector.name}: canonical=${actual} sha256=${sha256}`);
  console.log(`[PASS] Java/Node shared JCS golden vector: ${vector.name}`);
}
for (const raw of ['{"bad":"\\ud800"}', '{"bad":"\\udc00"}', '{"\\ud800":1}']) {
  let rejected = false;
  try { checkUnicode(JSON.parse(raw)); } catch { rejected = true; }
  if (!rejected) throw new Error('Invalid Unicode accepted by Node JCS oracle');
}
console.log(`[PASS] ${suite.vectors.length} shared JCS vectors + three malformed Unicode negatives`);
