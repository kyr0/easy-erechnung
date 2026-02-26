#!/usr/bin/env node
/**
 * End-to-end test for the OCR pipeline.
 *
 * Runs src/ocr.ts against the demo invoice and validates the output.
 * LLM connection settings are read from CLI args or env vars so the
 * same test suite works against local Ollama and remote endpoints.
 *
 * Usage:
 *   bun run src/e2e.ts
 *   bun run src/e2e.ts --base-url http://localhost:11434 \
 *                      --ocr-model glm-ocr:q8_0 \
 *                      --json-model qwen3:4b-q8_0
 *
 * Env vars (all optional, CLI flags take precedence):
 *   LLM_BASE_URL   LLM_API_KEY   OCR_MODEL   JSON_MODEL
 */

import { execSync, spawnSync, spawn } from 'child_process';
import { readFileSync, existsSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const projectRoot = join(__dirname, '..');

const DEMO_PDF        = join(projectRoot, 'demo', 'verify.pdf');
const EXPECTED_AI_JSON = join(projectRoot, 'demo', '_verify.ai.json');

// Defaults used when not provided via CLI or env
const DEFAULT_SELLER_ADDRESS = 'August-Horch-Str. 16, 80999 München';
const DEFAULT_SELLER_TAX_NO  = '147/214/70378';

let exitCode = 0;

function fail(msg: string): void {
  console.error(`\n❌ FAIL: ${msg}`);
  exitCode = 1;
}
function pass(msg: string): void {
  console.log(`✅ PASS: ${msg}`);
}
function info(msg: string): void {
  console.log(`ℹ️  ${msg}`);
}

// ── CLI / env config ──────────────────────────────────────────────────────────

interface E2EConfig {
  sellerAddress: string;
  sellerTaxNo:   string;
  baseUrl:       string;
  apiKey:        string;
  ocrModel:      string;
  jsonModel:     string;
}

function parseConfig(): E2EConfig {
  const argv = process.argv.slice(2);
  const cfg: E2EConfig = {
    sellerAddress: DEFAULT_SELLER_ADDRESS,
    sellerTaxNo:   DEFAULT_SELLER_TAX_NO,
    baseUrl:   process.env.LLM_BASE_URL  ?? 'http://localhost:11434',
    apiKey:    process.env.LLM_API_KEY   ?? 'no-key',
    ocrModel:  process.env.OCR_MODEL     ?? 'glm-ocr:q8_0',
    jsonModel: process.env.JSON_MODEL    ?? 'qwen3:4b-q8_0',
  };
  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case '--seller-address': cfg.sellerAddress = argv[++i]; break;
      case '--seller-tax-no':  cfg.sellerTaxNo   = argv[++i]; break;
      case '--base-url':       cfg.baseUrl       = argv[++i]; break;
      case '--api-key':        cfg.apiKey        = argv[++i]; break;
      case '--ocr-model':      cfg.ocrModel      = argv[++i]; break;
      case '--json-model':     cfg.jsonModel     = argv[++i]; break;
    }
  }
  // Strip trailing /v1 for consistency with ocr.ts
  cfg.baseUrl = cfg.baseUrl.replace(/\/v1\/?$/, '').replace(/\/$/, '');
  return cfg;
}

function isLocalhost(url: string): boolean {
  try {
    const { hostname } = new URL(url);
    return hostname === 'localhost' || hostname === '127.0.0.1';
  } catch {
    return false;
  }
}

// ── Ollama helpers (localhost only) ───────────────────────────────────────────

function checkOllamaInstalled(): boolean {
  try {
    execSync('command -v ollama', { stdio: 'pipe' });
    return true;
  } catch {
    return false;
  }
}

async function isServerRunning(baseUrl: string): Promise<boolean> {
  try {
    const res = await fetch(`${baseUrl}/v1/models`, { signal: AbortSignal.timeout(5000) });
    return res.ok;
  } catch {
    return false;
  }
}

async function startOllama(baseUrl: string): Promise<void> {
  info('Ollama server not running — starting it now...');
  const child = spawn('ollama', ['serve'], {
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
  });

  child.stderr?.on('data', (data: Buffer) => {
    const msg = data.toString().trim();
    if (msg) console.error(`  [ollama serve] ${msg}`);
  });

  child.on('error', (err) => {
    fail(`Could not start ollama serve: ${err.message}`);
    process.exit(1);
  });

  child.unref();

  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (await isServerRunning(baseUrl)) {
      pass('Ollama server started successfully');
      return;
    }
    await new Promise(r => setTimeout(r, 500));
  }

  fail('Ollama server did not start within 30 seconds');
  process.exit(1);
}

// ── Run the pipeline ──────────────────────────────────────────────────────────

interface PipelineResult { exitCode: number; stdout: string; stderr: string }

function runPipeline(cfg: E2EConfig, inputPath: string, outputPath: string): PipelineResult {
  // Build the arg list for ocr.ts  (no shell — spawnSync takes an array)
  const pipelineArgs = [
    'run', join(projectRoot, 'src', 'ocr.ts'),
    '--input',          inputPath,
    '--output',         outputPath,
    '--seller-address', cfg.sellerAddress,
    '--seller-tax-no',  cfg.sellerTaxNo,
    '--base-url',       cfg.baseUrl,
    '--api-key',        cfg.apiKey,
    '--ocr-model',      cfg.ocrModel,
    '--json-model',     cfg.jsonModel,
  ];

  info(`Running: bun ${pipelineArgs.join(' ')}`);

  const result = spawnSync('bun', pipelineArgs, {
    cwd: projectRoot,
    timeout: 300_000,
    encoding: 'utf-8',
  });

  return {
    exitCode: result.status ?? 1,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? '',
  };
}

// ── Validation ────────────────────────────────────────────────────────────────

function validateJsonOutput(outputPath: string): void {
  if (!existsSync(outputPath)) {
    fail(`Output file was not created at ${outputPath}`);
    return;
  }

  let raw: string;
  try {
    raw = readFileSync(outputPath, 'utf-8');
  } catch (err) {
    fail(`Could not read output file: ${err}`);
    return;
  }

  if (!raw.trim()) {
    fail('Output file is empty');
    return;
  }

  // ocr.ts now writes a single invoice JSON object, not an array
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(raw) as Record<string, unknown>;
  } catch (err) {
    fail(`Output is not valid JSON: ${err}`);
    console.error('  Raw output (first 500 chars):', raw.slice(0, 500));
    return;
  }

  if (Array.isArray(parsed)) {
    fail('Output is an array — expected a single invoice object; did you run the old pipeline?');
    return;
  }

  pass('Output is valid JSON');

  const invoice = parsed?.Invoice ?? parsed?.invoice;
  if (!invoice || typeof invoice !== 'object') {
    fail('No top-level "Invoice" key found in output');
    return;
  }

  const inv = invoice as Record<string, unknown>;
  const requiredFields = ['InvoiceNumber', 'InvoiceDate', 'Seller', 'Buyer', 'InvoiceLines'];
  const missing = requiredFields.filter(f => !(f in inv));
  if (missing.length > 0) {
    fail(`Missing required fields: ${missing.join(', ')}`);
  } else {
    pass('All required invoice fields present');
  }

  // Compare against the known-good fixture (best-effort, informational only)
  if (existsSync(EXPECTED_AI_JSON)) {
    try {
      const expected = JSON.parse(readFileSync(EXPECTED_AI_JSON, 'utf-8'));
      const expectedInv = (expected?.Invoice ?? expected?.invoice) as Record<string, unknown> | undefined;
      if (expectedInv) {
        if (inv.InvoiceNumber === expectedInv.InvoiceNumber) {
          pass(`Invoice number matches fixture (${inv.InvoiceNumber})`);
        } else {
          // Not a hard fail — different models/quantisations extract differently.
          // Update demo/_verify.ai.json if the new pipeline output is the ground truth.
          info(`Invoice number differs from fixture: got "${inv.InvoiceNumber}", fixture has "${expectedInv.InvoiceNumber}" (non-fatal)`);
        }
      }
    } catch {
      info('Could not read/parse expected AI fixture for comparison');
    }
  }
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  const cfg = parseConfig();

  console.log('╔══════════════════════════════════════════╗');
  console.log('║   OCR Pipeline — End-to-End Test        ║');
  console.log('╚══════════════════════════════════════════╝\n');
  info(`LLM endpoint : ${cfg.baseUrl}`);
  info(`OCR model    : ${cfg.ocrModel}`);
  info(`JSON model   : ${cfg.jsonModel}`);
  console.log('');

  // 1. Pre-flight checks
  info('Checking prerequisites...');

  if (!existsSync(DEMO_PDF)) {
    fail(`Demo PDF not found at ${DEMO_PDF}`);
    process.exit(1);
  }
  pass('Demo PDF exists');

  // 2. Ollama availability (only relevant when targeting localhost)
  if (isLocalhost(cfg.baseUrl)) {
    if (!checkOllamaInstalled()) {
      fail('Ollama is not installed. Run setup.sh or install from https://ollama.com');
      process.exit(1);
    }
    pass('Ollama is installed');

    if (await isServerRunning(cfg.baseUrl)) {
      pass('Ollama server is already running');
    } else {
      await startOllama(cfg.baseUrl);
    }
  } else {
    info(`Remote endpoint — skipping Ollama startup check`);
  }

  // 3. Run the pipeline
  info('Running OCR pipeline on demo invoice...');
  const outputPath = join(projectRoot, 'demo', '_e2e_output.json');
  const result = runPipeline(cfg, DEMO_PDF, outputPath);

  if (result.stderr) {
    console.error('\n--- Pipeline stderr ---');
    console.error(result.stderr);
    console.error('--- end stderr ---\n');
  }

  if (result.exitCode !== 0) {
    fail(`Pipeline exited with code ${result.exitCode}`);
    if (result.stdout) {
      console.error('stdout:', result.stdout.slice(0, 1000));
    }
  } else {
    pass('Pipeline exited successfully (code 0)');

    // 4. Validate output (only when pipeline succeeded)
    info('Validating output...');
    validateJsonOutput(outputPath);
  }

  // 5. Summary
  console.log('\n' + '═'.repeat(44));
  if (exitCode === 0) {
    console.log('🎉 All e2e checks passed!');
  } else {
    console.error('⚠️  Some e2e checks failed — see errors above.');
  }

  process.exit(exitCode);
}

main().catch((err) => {
  console.error(`\nFATAL UNHANDLED ERROR: ${err instanceof Error ? err.message : String(err)}`);
  if (err instanceof Error && err.stack) {
    console.error(err.stack);
  }
  process.exit(1);
});
