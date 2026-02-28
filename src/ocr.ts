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
 *     [--ocr-model glm-ocr:q8_0] [--json-model qwen3:4b-q8_0]
 *
 * Env vars (all optional, CLI flags take precedence):
 *   LLM_BASE_URL   LLM_API_KEY   OCR_MODEL   JSON_MODEL
 */

import { exec, spawn } from 'child_process';
import { readFileSync, writeFileSync, copyFileSync, mkdirSync, rmSync } from 'fs';
import { extname, join, dirname } from 'path';
import { parse, differenceInDays, isValid } from 'date-fns';
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
    public readonly model: string,
    public readonly reasoning: boolean,
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
      console.log('Ollama-Server läuft bereits.');
      return;
    }

    if (!(await this.checkOllamaInstalled())) {
      console.error('FEHLER: Ollama ist nicht installiert. Bitte installieren: https://ollama.com');
      process.exit(1);
    }

    console.log('Ollama-Server läuft nicht. Wird gestartet...');
    const ollamaServe = spawn('ollama', ['serve'], {
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });

    ollamaServe.stderr?.on('data', (data: Buffer) => {
      const msg = data.toString().trim();
      if (msg) console.error(`[ollama serve] ${msg}`);
    });

    ollamaServe.on('error', (err) => {
      console.error(`FEHLER: ollama serve konnte nicht gestartet werden: ${err.message}`);
      process.exit(1);
    });

    ollamaServe.unref();

    const maxWaitMs = 30_000;
    const start = Date.now();
    while (Date.now() - start < maxWaitMs) {
      if (await this.isServerRunning()) {
        console.log('Ollama-Server läuft jetzt.');
        return;
      }
      await new Promise(r => setTimeout(r, 500));
    }

    console.error(`FEHLER: Ollama-Server wurde innerhalb von ${maxWaitMs / 1000}s nicht bereit.`);
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

    console.log(`Lade Modell ${model} herunter...`);
    const child = spawn('ollama', ['pull', model], { stdio: 'inherit' });
    await new Promise<void>((resolve, reject) => {
      child.on('close', (code: number) => {
        if (code === 0) resolve();
        else reject(new Error(`Modell ${model} konnte nicht geladen werden: Exit-Code ${code}`));
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
        model: this.model,
        messages: [
          {
            role: 'user',
            content: 'Text Recognition:',
            images: [base64],
          },
        ],
        stream: false,
        ...(this.reasoning && { options: { num_ctx: 16384 } }),
      }),
    });

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`OCR-Anfrage fehlgeschlagen (Ollama /api/chat): HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { message?: { content?: string } };
    const content = data.message?.content;
    if (!content) throw new Error('OCR-Modell lieferte eine leere Antwort');
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
        model: this.model,
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
        ...(this.reasoning && { reasoning_effort: 'high' }),
      }),
    });

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`OCR-Anfrage fehlgeschlagen: HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { choices?: Array<{ message?: { content?: string } }> };
    const content = data.choices?.[0]?.message?.content;
    if (!content) throw new Error('OCR-Modell lieferte eine leere Antwort');
    return content.trim();
  }

  /**
   * Sends the OCR markdown to the JSON model and returns the extracted invoice JSON string.
   */
  async convertMarkdownToJson(
    markdown: string,
    mode: 'eingang' | 'ausgang',
    myCompany: { name: string; street: string; hausnr: string; plz: string; ort: string; land: string; taxId: string },
  ): Promise<string> {

    // Build the street line: combine street + hausnr if both present
    const streetLine = [myCompany.street, myCompany.hausnr].filter(Boolean).join(' ');

    // Build the "known company" block that's injected depending on mode
    const myBlock = [
      myCompany.name,
      streetLine,
      [myCompany.plz, myCompany.ort].filter(Boolean).join(' '),
      myCompany.land,
      `Steuernummer/USt-ID: ${myCompany.taxId}`,
    ].filter(Boolean).join('\n');

    // Eingang: user is the Buyer; Ausgang: user is the Seller
    const fixedRole = mode === 'eingang' ? 'Buyer' : 'Seller';
    const extractRole = mode === 'eingang' ? 'Seller' : 'Buyer';
    const fixedLabel = mode === 'eingang' ? 'Rechnung an (Empfänger)' : 'Rechnung von (Absender)';
    const extractLabel = mode === 'eingang' ? 'Rechnung von (Absender)' : 'Rechnung an (Empfänger)';

    const forbiddenFields = mode === 'ausgang'
      ? `- The ${extractRole} fields and PaymentReceiver must NEVER contain any of the known company values listed above. If the OCR text is ambiguous, leave those fields empty.`
      : `- The ${extractRole} fields must NEVER contain any of the known company values listed above. If the OCR text is ambiguous, leave those fields empty.`;

    const systemMessage = `You are an expert OCR data analyst and accountant.
Your task: extract invoice data from OCR'd text and output a single JSON object matching the ZUGFeRD invoice format.

The OCR text may come from multiple pages of the same invoice, separated by "--- PAGE N ---" markers.
You must semantically merge all pages into ONE unified invoice. Different pages may contain different parts of the same invoice (e.g. page 1 has line items, page 2 has payment details/IBAN).

MODE: ${mode.toUpperCase()} — the user's own company is the ${fixedRole} (${fixedLabel}).

Known company data (ALWAYS use these for the ${fixedRole} section):
${myBlock}

Fundamental rules:
- The ${fixedRole} section MUST use EXACTLY the known company data above. Do NOT extract ${fixedRole} data from the document.
- The ${extractRole} (${extractLabel}) must be extracted from the OCR document text.
${forbiddenFields}
- Extract data ONLY from the OCR'd document text below. Do NOT invent or hallucinate values.
- If a field cannot be found in any page, leave it as an empty string "" or 0.0 for numbers.
- TaxIdentificationNumber fields must contain ONLY a valid USt-IdNr (e.g. "DE123456789") or Steuernummer (e.g. "147/214/00001"). Customer numbers, mandate references, or other IDs are NOT tax IDs — leave the field as "" if no valid tax ID is found.
- InvoiceNumber: look for patterns like "Invoice #", "Rechnungsnummer:", "RE-", "INV-" near the top of the document. Do NOT use LineID values as InvoiceNumber.
- LineID values ("1", "2", "3") are position numbers in the InvoiceLines array, NOT the InvoiceNumber.
- IBAN, BIC, and BankName usually appear at the very top or very bottom of a page. Look in headers/footers across all pages.
- NEVER calculate or assume prices, quantities, or totals. Always use the exact numbers written in the document.
- Unit detection for line items: Use DAY as the unit ONLY if BOTH conditions are met: (1) the line item description contains a date range, AND (2) the quantity value for that position exactly matches the number of days in that date range. If the quantity is larger than the day count, the unit is likely HUR (hours), not DAY. The preprocessed text may include a "DAYS: N" annotation for reference.
- Combine line items from ALL pages into one InvoiceLines array. Do NOT duplicate items that appear on multiple pages.
- PaymentReference: ONLY extract an actual payment reference/Verwendungszweck if one is explicitly stated in the document (a distinct number or code). Do NOT copy the invoice number or any other field. If no explicit payment reference is found, set it to "".
- Return ONLY the raw JSON object. No markdown, no code fences, no explanation.`;

    const userMessage = `OCR'd invoice text:
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
      "Name": "${mode === 'ausgang' ? myCompany.name : '<from document>'}",
      "StreetName": "${mode === 'ausgang' ? streetLine : '<from document>'}",
      "City": "${mode === 'ausgang' ? myCompany.ort : '<from document>'}",
      "PostalCode": "${mode === 'ausgang' ? myCompany.plz : '<from document>'}",
      "CountryCode": "${mode === 'ausgang' ? myCompany.land : 'DE'}",
      "TaxIdentificationNumber": "${mode === 'ausgang' ? myCompany.taxId : '<from document or empty>'}"
    },
    "Buyer": {
      "Name": "${mode === 'eingang' ? myCompany.name : '<from document>'}",
      "StreetName": "${mode === 'eingang' ? streetLine : '<from document>'}",
      "City": "${mode === 'eingang' ? myCompany.ort : '<from document>'}",
      "PostalCode": "${mode === 'eingang' ? myCompany.plz : '<from document>'}",
      "CountryCode": "${mode === 'eingang' ? myCompany.land : 'DE'}",
      "TaxIdentificationNumber": "${mode === 'eingang' ? myCompany.taxId : '<from document or empty>'}"
    },
    "DocumentCurrencyCode": "EUR",
    "IBAN": "<IBAN>",
    "BIC": "<BIC>",
    "BankName": "<bank name>",
    "PaymentReceiver": "<payment receiver name>",
    "PaymentReference": "<ONLY if explicitly stated, otherwise empty string>",
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
      model: this.model,
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

    console.log(`[JSON] Sende an LLM (Modell: ${this.model})...`);
    const t0 = Date.now();

    const response = await fetch(url, {
      method: 'POST',
      signal: AbortSignal.timeout(5 * 60_000),
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    console.log(`[JSON] LLM-Antwort in ${((Date.now() - t0) / 1000).toFixed(1)}s — HTTP ${response.status}`);

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`JSON-Extraktion fehlgeschlagen (Ollama /api/chat): HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { message?: { content?: string } };
    const content = data.message?.content;
    if (!content) throw new Error(`JSON-Modell (${this.model}) lieferte eine leere Antwort`);

    return extractJson(content);
  }

  /** OpenAI-compatible /v1/chat/completions for remote endpoints */
  private async convertMarkdownViaOpenAI(systemMessage: string, userMessage: string): Promise<string> {
    const url = `${this.baseUrl}/v1/chat/completions`;
    console.log(`[JSON] Sende an LLM (Modell: ${this.model})...`);
    const t0 = Date.now();

    const response = await fetch(url, {
      method: 'POST',
      signal: AbortSignal.timeout(5 * 60_000),
      headers: this.authHeaders(),
      body: JSON.stringify({
        model: this.model,
        messages: [
          { role: 'system', content: systemMessage },
          { role: 'user', content: userMessage },
        ],
        stream: false,
        temperature: 0,
        response_format: { type: 'json_object' },
        ...(this.reasoning ? { reasoning_effort: 'high' } : { options: { num_ctx: 10240 } }),
      }),
    });

    console.log(`[JSON] LLM-Antwort in ${((Date.now() - t0) / 1000).toFixed(1)}s — HTTP ${response.status}`);

    if (!response.ok) {
      const body = await response.text().catch(() => '(no body)');
      throw new Error(`JSON-Extraktion fehlgeschlagen: HTTP ${response.status} — ${body}`);
    }

    const data = await response.json() as { choices?: Array<{ message?: { content?: string } }> };
    const content = data.choices?.[0]?.message?.content;
    if (!content) throw new Error(`JSON-Modell (${this.model}) lieferte eine leere Antwort`);

    return extractJson(content);
  }
}

/**
 * Extract a JSON object from LLM output that may contain thinking tokens,
 * markdown fences, or preamble/trailing text.
 */
function extractJson(raw: string): string {
  let text = raw.trim();

  // Strip <think>...</think> blocks (qwen3 extended thinking)
  text = text.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();

  // Strip markdown code fences
  text = text.replace(/^```(?:json)?\s*/i, '').replace(/\s*```\s*$/, '').trim();

  // Try parsing directly first
  try {
    JSON.parse(text);
    return text;
  } catch { /* continue with extraction */ }

  // Find the first '{' and match it to its closing '}'
  const start = text.indexOf('{');
  if (start !== -1) {
    let depth = 0;
    let inString = false;
    let escaped = false;
    for (let i = start; i < text.length; i++) {
      const ch = text[i];
      if (escaped) { escaped = false; continue; }
      if (ch === '\\' && inString) { escaped = true; continue; }
      if (ch === '"') { inString = !inString; continue; }
      if (inString) continue;
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) {
          const candidate = text.substring(start, i + 1);
          JSON.parse(candidate); // validate — throws if invalid
          return candidate;
        }
      }
    }
  }

  throw new Error('LLM-Antwort enthält kein gültiges JSON-Objekt');
}

// ── Markdown preprocessing ────────────────────────────────────────────────────

/** Common date formats found on German/European invoices */
const DATE_FORMATS = ['dd.MM.yyyy', 'dd/MM/yyyy', 'yyyy-MM-dd', 'dd-MM-yyyy'];

/**
 * Scan each line for date ranges (two dates on the same line).
 * When found, calculate the difference in days and append "DAYS: N".
 */
function preprocessMarkdownDates(markdown: string): string {
  // Match date-like tokens: dd.MM.yyyy, dd/MM/yyyy, yyyy-MM-dd, dd-MM-yyyy
  const dateTokenRe = /\b(\d{1,4}[.\-/]\d{1,2}[.\-/]\d{1,4})\b/g;

  return markdown
    .split('\n')
    .map(line => {
      const tokens: Date[] = [];
      for (const m of line.matchAll(dateTokenRe)) {
        for (const fmt of DATE_FORMATS) {
          const d = parse(m[1], fmt, new Date());
          if (isValid(d) && d.getFullYear() >= 1990 && d.getFullYear() <= 2099) {
            tokens.push(d);
            break;
          }
        }
      }
      // If we found exactly a date range (2 dates), annotate with day count
      if (tokens.length >= 2) {
        const days = Math.abs(differenceInDays(tokens[tokens.length - 1], tokens[0]));
        if (days > 0 && !line.includes('DAYS:')) {
          return `${line}  DAYS: ${days}`;
        }
      }
      return line;
    })
    .join('\n');
}

// ── File helpers ──────────────────────────────────────────────────────────────

async function detectFileType(filePath: string): Promise<'pdf' | 'image'> {
  const ext = extname(filePath).toLowerCase();
  if (ext === '.pdf') return 'pdf';
  if (['.jpg', '.jpeg', '.png', '.webp'].includes(ext)) return 'image';
  throw new Error(`Nicht unterstützter Dateityp: ${ext}`);
}

async function convertPdfToImages(pdfPath: string, tempDir: string): Promise<string[]> {
  console.log(`PDF wird in Bilder konvertiert: ${pdfPath}`);
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
  console.log(`${document.length} Seiten aus PDF extrahiert`);
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

  // Convert to greyscale then threshold so even light grey text becomes black
  image.greyscale();
  // Scan every pixel: anything below the threshold → black, else → white
  const threshold = 180; // 0–255; lighter greys (< 180) become black
  image.scan(0, 0, image.getWidth(), image.getHeight(), function (_x, _y, idx) {
    const grey = this.bitmap.data[idx]; // R=G=B after greyscale
    const val = grey < threshold ? 0 : 255;
    this.bitmap.data[idx]     = val; // R
    this.bitmap.data[idx + 1] = val; // G
    this.bitmap.data[idx + 2] = val; // B
    // alpha (idx+3) untouched
  });

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
  mode: 'eingang' | 'ausgang';
  myName: string;
  myStreet: string;
  myHausnr: string;
  myPlz: string;
  myOrt: string;
  myLand: string;
  myTaxId: string;
  // JSON Modell settings
  jsonBaseUrl: string;
  jsonApiKey: string;
  jsonModel: string;
  jsonReasoning: boolean;
  // OCR Modell settings
  ocrBaseUrl: string;
  ocrApiKey: string;
  ocrModel: string;
  ocrReasoning: boolean;
}

function parseArgs(): ParsedArgs {
  const argv = process.argv.slice(2);
  const result: ParsedArgs = {
    input: '',
    output: '',
    mode: 'eingang',
    myName: '',
    myStreet: '',
    myHausnr: '',
    myPlz: '',
    myOrt: '',
    myLand: 'DE',
    myTaxId: '',
    // JSON Modell defaults
    jsonBaseUrl:   process.env.JSON_BASE_URL  ?? 'http://localhost:11434',
    jsonApiKey:    process.env.JSON_API_KEY   ?? '',
    jsonModel:     process.env.JSON_MODEL     ?? 'qwen3:4b-q8_0',
    jsonReasoning: process.env.JSON_REASONING === 'true',
    // OCR Modell defaults
    ocrBaseUrl:    process.env.OCR_BASE_URL   ?? 'http://localhost:11434',
    ocrApiKey:     process.env.OCR_API_KEY    ?? '',
    ocrModel:      process.env.OCR_MODEL      ?? 'glm-ocr:q8_0',
    ocrReasoning:  process.env.OCR_REASONING  === 'true',
  };

  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case '--input':          result.input         = argv[++i]; break;
      case '--output':         result.output        = argv[++i]; break;
      case '--mode':           result.mode          = argv[++i] as 'eingang' | 'ausgang'; break;
      case '--my-name':        result.myName        = argv[++i]; break;
      case '--my-street':      result.myStreet      = argv[++i]; break;
      case '--my-hausnr':      result.myHausnr      = argv[++i]; break;
      case '--my-plz':         result.myPlz         = argv[++i]; break;
      case '--my-ort':         result.myOrt         = argv[++i]; break;
      case '--my-land':        result.myLand         = argv[++i]; break;
      case '--my-tax-id':      result.myTaxId       = argv[++i]; break;
      // JSON Modell settings
      case '--json-base-url':  result.jsonBaseUrl   = argv[++i]; break;
      case '--json-api-key':   result.jsonApiKey    = argv[++i]; break;
      case '--json-model':     result.jsonModel     = argv[++i]; break;
      case '--json-reasoning': result.jsonReasoning = argv[++i] === 'true'; break;
      // OCR Modell settings
      case '--ocr-base-url':   result.ocrBaseUrl    = argv[++i]; break;
      case '--ocr-api-key':    result.ocrApiKey     = argv[++i]; break;
      case '--ocr-model':      result.ocrModel      = argv[++i]; break;
      case '--ocr-reasoning':  result.ocrReasoning  = argv[++i] === 'true'; break;
      // Legacy args — silently ignore
      case '--base-url':       i++; break;
      case '--api-key':        i++; break;
      case '--seller-address': i++; break;
      case '--seller-tax-no':  i++; break;
    }
  }

  if (!result.input) {
    console.error('Verwendung: ocr --input <Datei> [--output <Datei>] [--mode eingang|ausgang]');
    console.error('               [--my-name <Name>] [--my-street <Straße>] [--my-hausnr <Nr>]');
    console.error('               [--my-plz <PLZ>] [--my-ort <Ort>] [--my-land <Land>] [--my-tax-id <StNr/UStID>]');
    console.error('               [--json-base-url <URL>] [--json-api-key <Schlüssel>] [--json-model <Modell>] [--json-reasoning <true|false>]');
    console.error('               [--ocr-base-url <URL>] [--ocr-api-key <Schlüssel>] [--ocr-model <Modell>] [--ocr-reasoning <true|false>]');
    console.error('');
    console.error('Umgebungsvariablen: JSON_BASE_URL  JSON_API_KEY  JSON_MODEL  JSON_REASONING');
    console.error('                    OCR_BASE_URL   OCR_API_KEY   OCR_MODEL   OCR_REASONING');
    process.exit(1);
  }

  return result;
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  const args = parseArgs();
  const { input, output, mode } = args;

  const myCompany = {
    name: args.myName,
    street: args.myStreet,
    hausnr: args.myHausnr,
    plz: args.myPlz,
    ort: args.myOrt,
    land: args.myLand,
    taxId: args.myTaxId,
  };

  // Normalize base URLs: strip trailing /v1 so we can always append paths ourselves
  const jsonBaseUrl = args.jsonBaseUrl.replace(/\/v1\/?$/, '').replace(/\/$/, '');
  const ocrBaseUrl = args.ocrBaseUrl.replace(/\/v1\/?$/, '').replace(/\/$/, '');

  // Default empty apiKeys to 'no-key' for localhost — Ollama doesn't need one,
  // but an empty string can cause issues with /v1 compat endpoints.
  let jsonApiKey = args.jsonApiKey;
  if (!jsonApiKey && /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(jsonBaseUrl)) {
    jsonApiKey = 'no-key';
  }

  let ocrApiKey = args.ocrApiKey;
  if (!ocrApiKey && /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(ocrBaseUrl)) {
    ocrApiKey = 'no-key';
  }

  console.log('=== JSON Modell Konfiguration ===');
  console.log(`LLM-Endpunkt : ${jsonBaseUrl}`);
  console.log(`API-Schlüssel: ${jsonApiKey ? '(gesetzt)' : '(leer)'}`);
  console.log(`Modell       : ${args.jsonModel}`);
  console.log(`Reasoning    : ${args.jsonReasoning ? 'aktiviert' : 'deaktiviert'}`);
  console.log('');

  console.log('=== OCR Modell Konfiguration ===');
  console.log(`LLM-Endpunkt : ${ocrBaseUrl}`);
  console.log(`API-Schlüssel: ${ocrApiKey ? '(gesetzt)' : '(leer)'}`);
  console.log(`Modell       : ${args.ocrModel}`);
  console.log(`Reasoning    : ${args.ocrReasoning ? 'aktiviert' : 'deaktiviert'}`);
  console.log('');

  // Create separate LLM clients for JSON and OCR models
  const jsonLlm = new LLMClient(jsonBaseUrl, jsonApiKey, args.jsonModel, args.jsonReasoning);
  const ocrLlm = new LLMClient(ocrBaseUrl, ocrApiKey, args.ocrModel, args.ocrReasoning);

  console.log('Prüfe ob JSON LLM-Server erreichbar ist...');
  await jsonLlm.ensureServerRunning();

  console.log('Prüfe ob OCR LLM-Server erreichbar ist...');
  await ocrLlm.ensureServerRunning();

  console.log(`Prüfe ob OCR-Modell "${args.ocrModel}" verfügbar ist...`);
  await ocrLlm.ensureModelReady(args.ocrModel);

  console.log(`Prüfe ob JSON-Modell "${args.jsonModel}" verfügbar ist...`);
  await jsonLlm.ensureModelReady(args.jsonModel);

  const fileType = await detectFileType(input);
  const tempDir = join(__dirname, `.tmp_${createHash('md5').update(input + Date.now()).digest('hex')}`);

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
      console.log(`Verarbeite Seite ${pageNum}/${imagePaths.length}...`);
      console.log(`════════════════════════════════════════`);
      const resizedPath = join(tempDir, `resized_${i}.png`);
      await resizeImageToMaxMP(imagePaths[i], resizedPath, 3);

      // Write each page preview to a well-known location for the Java UI
      const previewPath = join(tmpdir(), `ocr_preview_${pageNum}.png`);
      copyFileSync(resizedPath, previewPath);
      console.log(`Vorschaubild für Seite ${pageNum} gespeichert unter ${previewPath}`);
      // Also keep the legacy single-file preview for backwards compat (first page)
      if (i === 0) {
        copyFileSync(resizedPath, join(tmpdir(), 'ocr_preview.png'));
      }

      try {
        console.log(`[PAGE ${pageNum}] Starte OCR (Modell: ${args.ocrModel})...`);
        const ocrT0 = Date.now();
        const markdown = await ocrLlm.runOCR(resizedPath);
        console.log(`[PAGE ${pageNum}] OCR abgeschlossen in ${((Date.now() - ocrT0) / 1000).toFixed(1)}s`);
        console.error(`[OCR markdown page ${pageNum}]\n${markdown}\n[/OCR markdown]`);

        results.push({ page: pageNum, markdown });
        console.log(`[PAGE ${pageNum}] ✓ OCR erfolgreich`);
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error);
        results.push({ page: pageNum, error: msg });
        console.error(`[PAGE ${pageNum}] ✗ OCR fehlgeschlagen: ${msg}`);
      }
    }

    // Report per-page errors
    const errorPages = results.filter(r => r.error);
    const successPages = results.filter(r => r.markdown);

    if (errorPages.length > 0) {
      for (const ep of errorPages) {
        console.error(`FEHLER: Seite ${ep.page} OCR fehlgeschlagen: ${ep.error}`);
      }
    }

    if (successPages.length === 0) {
      throw new Error('Alle Seiten fehlgeschlagen — kein OCR-Text konnte erzeugt werden');
    }

    // Combine all page markdowns into a single document for one LLM call
    console.log(`\n════════════════════════════════════════`);
    console.log(`[JSON] Extrahiere Rechnungsdaten aus ${successPages.length} Seite(n) (Modell: ${args.jsonModel})...`);
    console.log(`════════════════════════════════════════`);

    const combinedMarkdown = successPages
      .map(p => `--- PAGE ${p.page} ---\n${p.markdown}`)
      .join('\n\n');

    // Pre-process: annotate date ranges with day counts so the LLM can use them
    const preprocessedMarkdown = preprocessMarkdownDates(combinedMarkdown);

    const jsonT0 = Date.now();
    const invoiceJson = await jsonLlm.convertMarkdownToJson(preprocessedMarkdown, mode, myCompany);
    console.log(`[JSON] Extraktion abgeschlossen in ${((Date.now() - jsonT0) / 1000).toFixed(1)}s`);

    // Parse to validate and pretty-print
    const parsed = JSON.parse(invoiceJson);

    // If PaymentReference is empty, copy InvoiceNumber there
    if (parsed.Invoice) {
      const inv = parsed.Invoice;
      if (!inv.PaymentReference && inv.InvoiceNumber) {
        inv.PaymentReference = inv.InvoiceNumber;
      }
    }

    const finalJson = JSON.stringify(parsed, null, 2);

    if (output) {
      writeFileSync(output, finalJson, 'utf-8');
      console.log(`[JSON] Rechnungs-JSON gespeichert unter ${output}`);
    } else {
      console.log(finalJson);
    }
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error('SCHWERWIEGENDER FEHLER:', error instanceof Error ? error.message : String(error));
  if (error instanceof Error && error.stack) {
    console.error(error.stack);
  }
  process.exit(1);
});
