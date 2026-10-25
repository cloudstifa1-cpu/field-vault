// Scrambles the app's code before it goes into the APK, so the linking engine is very hard to read or copy.
// The readable index.html stays in the private repo; only the scrambled copy (dist/index.html) is shipped.
import fs from 'node:fs';
import path from 'node:path';
import JavaScriptObfuscator from 'javascript-obfuscator';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const src = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

const options = {
  compact: true,
  controlFlowFlattening: true,
  controlFlowFlatteningThreshold: 0.5,
  deadCodeInjection: true,
  deadCodeInjectionThreshold: 0.2,
  identifierNamesGenerator: 'hexadecimal',
  renameGlobals: false,          // keeps window.fvBack working for the Android back button
  selfDefending: true,           // breaks if someone reformats the code to read it
  stringArray: true,
  stringArrayEncoding: ['base64'],
  stringArrayRotate: true,
  stringArrayShuffle: true,
  stringArrayThreshold: 0.75,
  splitStrings: true,
  splitStringsChunkLength: 8,
  transformObjectKeys: true,
  unicodeEscapeSequence: false,
  numbersToExpressions: true,
  simplify: true,
};

let count = 0;
let out = src.replace(/<script>([\s\S]*?)<\/script>/g, (_, code) => {
  count++;
  const scrambled = JavaScriptObfuscator.obfuscate(code, options).getObfuscatedCode();
  return '<script>' + scrambled + '</script>';
});
// Strip HTML comments so build notes don't ship.
out = out.replace(/<!--[\s\S]*?-->/g, '');

fs.mkdirSync(path.join(root, 'dist'), { recursive: true });
fs.writeFileSync(path.join(root, 'dist', 'index.html'), out);
console.log(`Protected ${count} script block(s) -> dist/index.html (${out.length} bytes)`);
