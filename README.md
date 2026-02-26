# 🧾 easy-e-rechnung

**Java-App for creating and validating Factur-X / ZuGFeRD / X-Rechnung invoices conforming with EU-Norm EN 16931.**

---

## ✨ Key Features

| Feature | Description |
|---------|-------------|
| 🇪🇺 **EU Compliant** | Generates invoices conforming to **EN 16931**, accepted across all EU member states. |
| 🔒 **100% Offline & Private** | All processing happens locally on your machine. Your invoice data never leaves your computer. |
| 🤖 **LocalAI-Powered OCR** | Uses local, open-weight AI models for automatic, high-quality text extraction from PDF invoices. |
| 📄 **Multi-Page Support** | Processes multi-page PDF invoices — each page is OCR'd separately, then semantically merged by the LLM into a single structured result. |
| 🧠 **Works with Ollama** | Integrates with Ollama for local LLM inference. Default models: `glm-ocr:q8_0` (OCR) and `qwen3:4b-q8_0` (JSON extraction). |
| 💻 **Cross-Platform** | Runs on **macOS**, **Linux**, and **Windows**. (macOS and Linux are the primary tested platforms.) |

## 🚀 Setup

```bash
# Install tooling and dependencies
bash setup.sh
```

## 🧾 Usage

Users usually interact with the app via the GUI:

```bash
# Start the App (Java)
./start.sh
```

## 🧑‍💻 Calling the OCR pipeline via Shell

You can also run the OCR pipeline directly via shell:

```bash
# Single-page PDF
bun run src/ocr.ts --input demo/verify.pdf --output /tmp/result.json \
  --seller-address "Friedrich-Damm-Str. 8, 80999 München" \
  --seller-tax-no "147/214/00001"

# Multi-page PDF with custom models
bun run src/ocr.ts --input demo/verify_multipage.pdf --output /tmp/result.json \
  --seller-address "Friedrich-Damm-Str. 8, 80999 München" \
  --seller-tax-no "147/214/00001" \
  --ocr-model glm-ocr:q8_0 --json-model qwen3:4b-q8_0
```

### OCR Pipeline Architecture

1. **PDF → Images** — Each page is rendered as a high-resolution image.
2. **Image Preprocessing** — Contrast boost, normalization, and resize to max 3MP.
3. **OCR per Page** — Vision model (`glm-ocr:q8_0`) extracts text as markdown.
4. **Date Preprocessing** — Date ranges in the OCR text are annotated with day counts (e.g. `DAYS: 31`) to help the LLM correctly set quantities for time-based line items.
5. **JSON Extraction** — All page markdowns are combined and sent in a single LLM call (`qwen3:4b-q8_0`) to produce a structured ZUGFeRD-compatible JSON.

---

## 📸 How It Works

### Step 1: Drag & Drop Your Invoice PDF

Simply drag and drop a PDF invoice into the app. Multi-page PDFs are displayed with tabs on the left side. The AI-powered OCR will automatically extract the text from each page.

![OCR Detection](docs/easy_erechnung_app_ocr_detection.png)

---

### Step 2: AI Post-Processing

The local AI model analyzes the OCR output and intelligently extracts all relevant invoice data. Progress is shown in real-time with per-page OCR status tabs and a JSON extraction log.

![AI OCR Post-Processing](docs/easy_erechnung_app_ai_ocr_post.png)

---

### Step 3: Review Invoice Positions

Review and edit the extracted line items. The app calculates totals automatically.

![Invoice Positions](docs/easy_erechnung_app_positions.png)

---

### Step 4: Review Taxes & Totals

Verify the tax calculations and monetary summations.

![Taxes and Totals](docs/easy_erechnung_app_taxes.png)

---

### Step 5: Create the e-Invoice

Click to generate the ZuGFeRD/Factur-X compliant PDF with embedded XML.

![Create e-Invoice](docs/easy_erechnung_app_factur-x_zugferd_create.png)

---

### Step 6: Invoice Created Successfully

The app confirms successful creation and automatically opens the ELSTER e-Rechnung portal for official validation.

![Success](docs/easy_erechnung_app_successful_creation.png)

---

### Step 7: View the Final Output

Your new e-Invoice is ready.

![Done](docs/easy_erechnung_app_done.png)

---

## ✅ Validated by Official Tools

### ELSTER (German Tax Authority)

![Validated by ELSTER](docs/elster_validated.png)

### Other Validators

![Validated by Winball](docs/winball_validated.png)

---

## 📁 Demo Data

The `demo/` folder contains sample invoice data for testing and demonstration purposes.

---

## 📜 License

MIT, Open Source.

---

## 🛡️ Privacy Promise

- **No network requests.** All AI inference runs locally.
- **No telemetry.** Your data stays on your device.
- **Open Source.** Audit the code yourself.
