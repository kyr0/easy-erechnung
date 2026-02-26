#!/usr/bin/env node
/**
 * OCR pipeline — PDF/image → invoice JSON
 *
 * Uses any OpenAI-compatible /v1/chat/completions endpoint.
 * Defaults to a local Ollama instance and auto-starts it when needed.
 *
 * Usage:
 *   bun run src/ocr.ts --input invoice.pdf --output result.json \
 *     [--seller-address "Str. 1, 12345 City"] [--seller-tax-no "123/456/78900"] \
 *     [--base-url http://localhost:11434] [--api-key no-key] \
 *     [--ocr-model glm-ocr:q8_0] [--json-model qwen3:1.7b-q4_K_M]
 *
 * Env vars (all optional, CLI flags take precedence):
 *   LLM_BASE_URL   LLM_API_KEY   OCR_MODEL   JSON_MODEL
 */

import { exec, spawn } from 'child_process';
import { readFileSync, writeFileSync, copyFileSync, mkdirSync, rmSync } from 'fs';
import { extname, join, dirname } from 'path';
import { tmpdir } from 'os';
import { promisify } from 'util';
import { createHash } from 'crypto';
import { fileURLToPath } from 'url';

const execAsync = promisify(exec);
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// ── Types ─────────────────────────────────────────────────────────────────────

interface PageResult {
  page: number;
  markdown?: string;
  error?: string;
}

// ── LLMClient ─────────────────────────────────────────────────────────────────

/**
 * Thin wrapper around any OpenAI-compatible /v1/chat/completions endpoint.
 * baseUrl should NOT include a trailing /v1 — we append paths ourselves.
 */
class LLMClient {
  constructor(
    public readonly baseUrl: string,
    public readonly apiKey: string,
    public readonly ocrModel: string,
    public readonly jsonModel: string,
  ) {}

  private isLocalhost(): boolean {
    try {
      const { hostname } = new URL(this.baseUrl);
      return hostname === 'localhost' || hostname === '127.0.0.1';
    } catch {
      return false;
    }
  }

  private authHeaders(): Record<string, string> {
    const h: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.apiKey) h['Authorization'] = `Bearer ${this.apiKey}`;
    return h;
  }

  async isServerRunning(): Promise<boolean> {
    try {
      const res = await fetch(`${this.baseUrl}/v1/models`, {
        signal: AbortSignal.timeout(5000),
        headers: this.authHeaders(),
      });
      return res.ok;
    } catch {
      return false;
    }
  }

  private async checkOllamaInstalled(): Promise<boolean> {
    try {
      await execAsync('command -v ollama > /dev/null 2>&1');
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Auto-starts `ollama serve` when base URL is localhost and server is not up.
   * No-ops for remote endpoints.
   */
  async ensureServerRunning(): Promise<void> {
    if (!this.isLocalhost()) return;

    if (await this.isServerRunning()) {
      console.log('Ollama server is already running.');
      return;
    }

    if (!(await this.checkOllamaInstalled())) {
      console.error('ERROR: Ollama is not installed. Please install from https://ollama.com');
      process.exit(1);
    }

    console.log('Ollama server is not running. Starting it...');
    const ollamaServe = spawn('ollama', ['serve'], {
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });

    ollamaServe.stderr?.on('data', (data: Buffer) => {
      const msg = data.toString().trim();
      if (msg) console.error(`[ollama serve] ${msg}`);
    });

    ollamaServe.on('error', (err) => {
      console.error(`ERROR: Failed to start ollama serve: ${err.message}`);
      process.exit(1);
    });

    ollamaServe.unref();

    const maxWaitMs = 30_000;
    const start = Date.now();
    while (Date.now() - start < maxWaitMs) {
      if (await this.isServerRunning()) {
        console.log('Ollama server is now running.');
        return;
      }
      await new Promise(r => setTimeout(r, 500));
    }

    console.error(`ERROR: Ollama server did not become ready within ${maxWaitMs / 1000}s.`);
    process.exit(1);
  }

  /**
   * Ensures a model exists locally. Only attempts a pull when using localhost
   * (we can only `ollama pull` on a local Ollama instance).
   * Uses exact name matching so e.g. glm-ocr:q8_0 is pulled even if glm-ocr:q8_0 exists.
   */
  async ensureModelReady(model: string): Promise<void> {
    if (!this.isLocalhost()) return;

    try {
      const res = await fetch(`${this.baseUrl}/api/tags`, {
        signal: AbortSignal.timeout(5000),
      });
      if (res.ok) {
        const data = await res.json() as { models?: Array<{ name: string }> };
        const names = (data.models ?? []).map(m => m.name);
        if (names.includes(model)) {
          return; // exact model already present
        }
      }
    } catch {
      // ignore — try pulling
    }

    console.log(`Pulling model ${model}...`);
    const child = spawn('ollama', ['pull', model], { stdio: 'inherit' });
    await new Promise<void>((resolve, reject) => {
      child.on('close', (code: number) => {
        if (code === 0) resolve();
        else reject(new Error(`Failed to pull model ${model}: exit code ${code}`));
      });
      child.on('error', reject);
    });
  }

  /**
   * Sends an image to the OCR model.
   * For localhost (Ollama): uses native /api/chat with `images` array and
   * the required "Text Recognition:" prompt that glm-ocr expects.
   * For remote endpoints: falls back to OpenAI vision image_url format.
   */
  async runOCR(imagePath: string): Promise<string> {
    const imageData = readFileSync(imagePath);
    const base64 = imageData.toString('base64');

    if (this.isLocalhost()) {
      return this.runOCROllama(base64);
    }
    return this.runOCROpenAI(base64, imagePath);
  }

  /** Ollama-native /api/chat — required for glm-ocr ("Text Recognition:") */
  private async runOCROllama(base64: string): Promise<string> {
    const response = await fetch(`${this.baseUrl}/api/chat`, {
      method: 'POST',
      signal: AbortSignal.timeout(5 * 60_000),
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: this.ocrModel,
        messages: [
          {
            role: 'user',
            content: 'Text Recognition:',
            images: [base64],
          },
        ],
        stream: false,
      }),
    });

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`OCR request failed (Ollama /api/chat): HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { message?: { content?: string } };
    const content = data.message?.content;
    if (!content) throw new Error('OCR model returned an empty response');
    return content.trim();
  }

  /** OpenAI-compatible vision fallback for remote endpoints */
  private async runOCROpenAI(base64: string, imagePath: string): Promise<string> {
    const mimeType = imagePath.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';

    const response = await fetch(`${this.baseUrl}/v1/chat/completions`, {
      method: 'POST',
      signal: AbortSignal.timeout(5 * 60_000),
      headers: this.authHeaders(),
      body: JSON.stringify({
        model: this.ocrModel,
        messages: [
          {
            role: 'user',
            content: [
              {
                type: 'image_url',
                image_url: { url: `data:${mimeType};base64,${base64}` },
              },
              {
                type: 'text',
                text: 'Perform complete text recognition on this invoice image. Extract EVERY piece of text on the page including: the header (company name, logo text), sender/seller name and full address, recipient/buyer name and full address, invoice number, invoice date, due date, all line items with quantities/prices, VAT/tax breakdown, totals, bank/payment details (IBAN, BIC, bank name), and any footer text. Preserve the document structure. Return the result as clean markdown.',
              },
            ],
          },
        ],
        stream: false,
      }),
    });

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`OCR request failed: HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { choices?: Array<{ message?: { content?: string } }> };
    const content = data.choices?.[0]?.message?.content;
    if (!content) throw new Error('OCR model returned an empty response');
    return content.trim();
  }

  /**
   * Sends the OCR markdown to the JSON model and returns the extracted invoice JSON string.
   */
  async convertMarkdownToJson(markdown: string, sellerAddress: string, sellerTaxNo: string): Promise<string> {
    const systemMessage = `You are an expert OCR data analyst and accountant.
Your task: extract invoice data from OCR'd text and output a single JSON object matching the ZUGFeRD invoice format.

The OCR text may come from multiple pages of the same invoice, separated by "--- PAGE N ---" markers.
You must semantically merge all pages into ONE unified invoice. Different pages may contain different parts of the same invoice (e.g. page 1 has line items, page 2 has payment details/IBAN).

Fundamental rules:
- Extract data ONLY from the OCR'd document text below. Do NOT invent or hallucinate values.
- If a field cannot be found in any page, leave it as an empty string "" or 0.0 for numbers.
- The "Seller" hints (address, tax number) are provided by the user as metadata for the ZUGFeRD output. Extract the actual seller name from the document.
- The "Buyer" is the recipient/addressee of the invoice — the person or company the invoice is sent TO. On German invoices the buyer's name and address appear in the address window block below the sender line (e.g. "Usegroup Inh. Jochen Stärk / Huswertstr. 14 / 60435 Frankfurt"). Extract the buyer Name, StreetName, City, and PostalCode from this block.
- TaxIdentificationNumber fields must contain ONLY a valid USt-IdNr (e.g. "DE123456789") or Steuernummer (e.g. "147/214/70378"). Customer numbers ("K0100077603"), mandate references, or other IDs are NOT tax IDs — leave the field as "" if no valid tax ID is found.
- InvoiceNumber: look for patterns like "Invoice #", "Rechnungsnummer:", "RE-", "INV-" near the top of the document. Do NOT use LineID values as InvoiceNumber.
- LineID values ("1", "2", "3") are position numbers in the InvoiceLines array, NOT the InvoiceNumber.
- Combine line items from ALL pages into one InvoiceLines array. Do NOT duplicate items that appear on multiple pages.
- Return ONLY the raw JSON object. No markdown, no code fences, no explanation.`;

    const userMessage = `Seller address hint (for Seller section): ${sellerAddress}
Seller Tax ID hint (for Seller.TaxIdentificationNumber): ${sellerTaxNo}

OCR'd invoice text:
${markdown}

IMPORTANT unit codes:
- HUR = per hour (also PT, MT)
- DAY = per day
- PCE = per unit

Tax percentages are plain numbers (7% → 7.00, 19% → 19.00).

Return ONLY valid JSON matching exactly this structure:
{
  "Invoice": {
    "InvoiceNumber": "<invoice number, e.g. RE-20264321/113>",
    "InvoiceDate": "YYYY-MM-DD",
    "DueDate": "YYYY-MM-DD",
    "Seller": {
      "Name": "<seller company name>",
      "StreetName": "<street from seller address hint>",
      "City": "<city from seller address hint>",
      "PostalCode": "<postal code from seller address hint>",
      "CountryCode": "DE",
      "TaxIdentificationNumber": "<from seller tax ID hint>"
    },
    "Buyer": {
      "Name": "<buyer name>",
      "StreetName": "<buyer street>",
      "City": "<buyer city>",
      "PostalCode": "<buyer postal code>",
      "CountryCode": "DE",
      "TaxIdentificationNumber": "<buyer tax ID>"
    },
    "DocumentCurrencyCode": "EUR",
    "IBAN": "<IBAN>",
    "BIC": "<BIC>",
    "BankName": "<bank name>",
    "PaymentReceiver": "<payment receiver name>",
    "PaymentReference": "<payment reference>",
    "Tax": {
      "TaxTypeCode": "VAT",
      "TaxCategoryCode": "S",
      "TaxPercentage": 0.0,
      "TaxAmount": 0.0
    },
    "MonetarySummation": {
      "LineTotal": 0.0,
      "TaxExclusiveAmount": 0.0,
      "TaxInclusiveAmount": 0.0,
      "PayableAmount": 0.0
    },
    "InvoiceLines": [
      {
        "LineID": "1",
        "ProductName": "<product/service description>",
        "Unit": "HUR",
        "Quantity": 0.0,
        "UnitPrice": 0.0,
        "LineTotalAmount": 0.0,
        "TaxCategoryCode": "S",
        "TaxPercentage": 0.0
      }
    ]
  }
}`;

    if (this.isLocalhost()) {
      return this.convertMarkdownViaOllama(systemMessage, userMessage);
    }
    return this.convertMarkdownViaOpenAI(systemMessage, userMessage);
  }

  /** Ollama-native /api/chat for JSON extraction — avoids /v1 auth issues */
  private async convertMarkdownViaOllama(systemMessage: string, userMessage: string): Promise<string> {
    const url = `${this.baseUrl}/api/chat`;
    const payload = {
      model: this.jsonModel,
      messages: [
        { role: 'system', content: systemMessage },
        { role: 'user', content: userMessage },
      ],
      stream: false,
      // Disable extended thinking (qwen3 etc.) — without this the model can
      // spend minutes generating hidden <think> tokens before responding.
      think: false,
      options: { temperature: 0, num_ctx: 10240 },
    };

    console.log(`JSON extraction via Ollama /api/chat (model: ${this.jsonModel}, think: false)...`);
    const t0 = Date.now();

    const response = await fetch(url, {
      method: 'POST',
      signal: AbortSignal.timeout(5 * 60_000),
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    console.log(`JSON extraction response in ${((Date.now() - t0) / 1000).toFixed(1)}s — HTTP ${response.status}`);

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`JSON extraction request failed (Ollama /api/chat): HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { message?: { content?: string } };
    const content = data.message?.content;
    if (!content) throw new Error(`JSON model (${this.jsonModel}) returned an empty response`);

    return content.trim()
      .replace(/^```(?:json)?\s*/i, '')
      .replace(/\s*```\s*$/, '')
      .trim();
  }

  /** OpenAI-compatible /v1/chat/completions for remote endpoints */
  private async convertMarkdownViaOpenAI(systemMessage: string, userMessage: string): Promise<string> {
    const url = `${this.baseUrl}/v1/chat/completions`;
    console.log(`JSON extraction via OpenAI-compat ${url} (model: ${this.jsonModel})...`);
    const t0 = Date.now();

    const response = await fetch(url, {
      method: 'POST',
      signal: AbortSignal.timeout(5 * 60_000),
      headers: this.authHeaders(),
      body: JSON.stringify({
        model: this.jsonModel,
        messages: [
          { role: 'system', content: systemMessage },
          { role: 'user', content: userMessage },
        ],
        stream: false,
        temperature: 0,
        options: { num_ctx: 10240 },
      }),
    });

    console.log(`JSON extraction response in ${((Date.now() - t0) / 1000).toFixed(1)}s — HTTP ${response.status}`);

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`JSON extraction request failed: HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { choices?: Array<{ message?: { content?: string } }> };
    const content = data.choices?.[0]?.message?.content;
    if (!content) throw new Error(`JSON model (${this.jsonModel}) returned an empty response`);

    return content.trim()
      .replace(/^```(?:json)?\s*/i, '')
      .replace(/\s*```\s*$/, '')
      .trim();
  }
}

// ── File helpers ──────────────────────────────────────────────────────────────

async function detectFileType(filePath: string): Promise<'pdf' | 'image'> {
  const ext = extname(filePath).toLowerCase();
  if (ext === '.pdf') return 'pdf';
  if (['.jpg', '.jpeg', '.png', '.webp'].includes(ext)) return 'image';
  throw new Error(`Unsupported file type: ${ext}`);
}

async function convertPdfToImages(pdfPath: string, tempDir: string): Promise<string[]> {
  console.log(`Converting PDF to images: ${pdfPath}`);
  const { pdf } = await import('pdf-to-img');
  const pdfBuffer = readFileSync(pdfPath);
  const document = await pdf(pdfBuffer, { scale: 3 });
  const imagePaths: string[] = [];
  let counter = 1;
  for await (const pageBuffer of document) {
    const outputPath = join(tempDir, `page_${counter}.png`);
    writeFileSync(outputPath, pageBuffer as unknown as Uint8Array);
    imagePaths.push(outputPath);
    counter++;
  }
  console.log(`Extracted ${document.length} pages from PDF`);
  return imagePaths;
}

async function resizeImageToMaxMP(inputPath: string, outputPath: string, maxMP = 3): Promise<void> {
  // https://github.com/jimp-dev/jimp
  // docs: https://jimp-dev.github.io/jimp/guides/getting-started/
  const Jimp = await import('jimp');
  const image = await Jimp.default.read(inputPath);

  // Boost contrast for better OCR: increase contrast and normalize
  image.contrast(0.75);
  image.normalize();

  const currentMP = (image.getWidth() * image.getHeight()) / 1_000_000;
  if (currentMP > maxMP) {
    const scale = Math.sqrt(maxMP / currentMP);
    image.resize(Math.floor(image.getWidth() * scale), Math.floor(image.getHeight() * scale));
  }
  const buffer = await image.getBufferAsync(Jimp.default.MIME_PNG);
  writeFileSync(outputPath, buffer as unknown as Uint8Array);
}

// ── CLI args ──────────────────────────────────────────────────────────────────

interface ParsedArgs {
  input: string;
  output: string;
  sellerAddress: string;
  sellerTaxNo: string;
  baseUrl: string;
  apiKey: string;
  ocrModel: string;
  jsonModel: string;
}

function parseArgs(): ParsedArgs {
  const argv = process.argv.slice(2);
  const result: ParsedArgs = {
    input: '',
    output: '',
    sellerAddress: '',
    sellerTaxNo: '',
    // Env-var defaults — CLI flags override these
    baseUrl:   process.env.LLM_BASE_URL  ?? 'http://localhost:11434',
    apiKey:    process.env.LLM_API_KEY   ?? '',
    ocrModel:  process.env.OCR_MODEL     ?? 'glm-ocr:q8_0',
    jsonModel: process.env.JSON_MODEL    ?? 'qwen3:1.7b-q4_K_M',
  };

  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case '--input':          result.input         = argv[++i]; break;
      case '--output':         result.output        = argv[++i]; break;
      case '--seller-address': result.sellerAddress = argv[++i]; break;
      case '--seller-tax-no':  result.sellerTaxNo   = argv[++i]; break;
      case '--base-url':       result.baseUrl       = argv[++i]; break;
      case '--api-key':        result.apiKey        = argv[++i]; break;
      case '--ocr-model':      result.ocrModel      = argv[++i]; break;
      case '--json-model':     result.jsonModel     = argv[++i]; break;
    }
  }

  if (!result.input) {
    console.error('Usage: ocr --input <file> [--output <file>]');
    console.error('           [--seller-address <addr>] [--seller-tax-no <taxno>]');
    console.error('           [--base-url <url>] [--api-key <key>]');
    console.error('           [--ocr-model <model>] [--json-model <model>]');
    console.error('');
    console.error('Env vars (overridden by CLI flags): LLM_BASE_URL  LLM_API_KEY  OCR_MODEL  JSON_MODEL');
    process.exit(1);
  }

  return result;
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  const args = parseArgs();
  const { input, output, sellerAddress, sellerTaxNo, ocrModel, jsonModel, baseUrl: rawBaseUrl } = args;

  // Normalize base URL: strip trailing /v1 so we can always append paths ourselves
  const baseUrl = rawBaseUrl.replace(/\/v1\/?$/, '').replace(/\/$/, '');

  // Default empty apiKey to 'no-key' for localhost — Ollama doesn't need one,
  // but an empty string can cause issues with /v1 compat endpoints.
  let apiKey = args.apiKey;
  if (!apiKey && /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(baseUrl)) {
    apiKey = 'no-key';
  }

  const llm = new LLMClient(baseUrl, apiKey, ocrModel, jsonModel);

  console.log(`LLM endpoint : ${baseUrl}`);
  console.log(`API key      : ${apiKey ? '(set)' : '(empty)'}`);
  console.log(`OCR model    : ${ocrModel}`);
  console.log(`JSON model   : ${jsonModel}`);
  console.log('');

  console.log('Ensuring LLM server is available...');
  await llm.ensureServerRunning();

  console.log(`Ensuring OCR model "${ocrModel}" is available...`);
  await llm.ensureModelReady(ocrModel);

  console.log(`Ensuring JSON model "${jsonModel}" is available...`);
  await llm.ensureModelReady(jsonModel);

  const fileType = await detectFileType(input);
  const tempDir = join(__dirname, '.tmp_' + createHash('md5').update(input + Date.now()).digest('hex'));

  try {
    mkdirSync(tempDir, { recursive: true });

    let imagePaths: string[];
    if (fileType === 'pdf') {
      imagePaths = await convertPdfToImages(input, tempDir);
    } else {
      imagePaths = [input];
    }

    const results: PageResult[] = [];

    for (let i = 0; i < imagePaths.length; i++) {
      const pageNum = i + 1;
      console.log(`\n════════════════════════════════════════`);
      console.log(`Processing page ${pageNum}/${imagePaths.length}...`);
      console.log(`════════════════════════════════════════`);
      const resizedPath = join(tempDir, `resized_${i}.png`);
      await resizeImageToMaxMP(imagePaths[i], resizedPath, 3);

      // Write each page preview to a well-known location for the Java UI
      const previewPath = join(tmpdir(), `ocr_preview_${pageNum}.png`);
      copyFileSync(resizedPath, previewPath);
      console.log(`Preview image for page ${pageNum} written to ${previewPath}`);
      // Also keep the legacy single-file preview for backwards compat (first page)
      if (i === 0) {
        copyFileSync(resizedPath, join(tmpdir(), 'ocr_preview.png'));
      }

      try {
        console.log(`[PAGE ${pageNum}] Running OCR (model: ${ocrModel})...`);
        const ocrT0 = Date.now();
        const markdown = await llm.runOCR(resizedPath);
        console.log(`[PAGE ${pageNum}] OCR completed in ${((Date.now() - ocrT0) / 1000).toFixed(1)}s`);
        console.error(`[OCR markdown page ${pageNum}]\n${markdown}\n[/OCR markdown]`);

        results.push({ page: pageNum, markdown });
        console.log(`[PAGE ${pageNum}] ✓ OCR successful`);
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error);
        results.push({ page: pageNum, error: msg });
        console.error(`[PAGE ${pageNum}] ✗ OCR FAILED: ${msg}`);
      }
    }

    // Report per-page errors
    const errorPages = results.filter(r => r.error);
    const successPages = results.filter(r => r.markdown);

    if (errorPages.length > 0) {
      for (const ep of errorPages) {
        console.error(`ERROR: Page ${ep.page} OCR failed: ${ep.error}`);
      }
    }

    if (successPages.length === 0) {
      throw new Error('All pages failed — no OCR text could be produced');
    }

    // Combine all page markdowns into a single document for one LLM call
    console.log(`\n════════════════════════════════════════`);
    console.log(`Extracting invoice JSON from ${successPages.length} page(s) (model: ${jsonModel})...`);
    console.log(`════════════════════════════════════════`);

    const combinedMarkdown = successPages
      .map(p => `--- PAGE ${p.page} ---\n${p.markdown}`)
      .join('\n\n');

    const jsonT0 = Date.now();
    const invoiceJson = await llm.convertMarkdownToJson(combinedMarkdown, sellerAddress, sellerTaxNo);
    console.log(`JSON extraction completed in ${((Date.now() - jsonT0) / 1000).toFixed(1)}s`);

    // Parse to validate and pretty-print
    let finalJson: string;
    try {
      const parsed = JSON.parse(invoiceJson);
      finalJson = JSON.stringify(parsed, null, 2);
    } catch {
      // If not valid JSON, use raw output — let downstream handle the error
      finalJson = invoiceJson;
    }

    if (output) {
      writeFileSync(output, finalJson, 'utf-8');
      console.log(`\nInvoice JSON written to ${output}`);
    } else {
      console.log(finalJson);
    }
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error('FATAL ERROR:', error instanceof Error ? error.message : String(error));
  if (error instanceof Error && error.stack) {
    console.error(error.stack);
  }
  process.exit(1);
});
