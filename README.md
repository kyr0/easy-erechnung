# 🧾 easy-e-rechnung

**Java-App for creating and validating Factur-X / ZuGFeRD / X-Rechnung invoices conforming with EU-Norm EN 16931.**

---

## ✨ Key Features

| Feature | Description |
|---------|-------------|
| 🇪🇺 **EU Compliant** | Generates invoices conforming to **EN 16931**, accepted across all EU member states. |
| 🔒 **100% Offline & Private** | All processing happens locally on your machine. Your invoice data never leaves your computer. |
| 🤖 **LocalAI-Powered OCR** | Uses local, open-weight AI models for automatic, high-quality text extraction from PDF invoices. |
| 🧠 **Works with Ollama & PaddlePaddle** | Integrates with popular local AI frameworks for flexibility. |
| 💻 **Cross-Platform** | Runs on **macOS**, **Linux**, and **Windows**. (macOS is the primary tested platform.) |

---

## 📸 How It Works

### Step 1: Drag & Drop Your Invoice PDF

Simply drag and drop a PDF invoice into the app. The AI-powered OCR will automatically extract the text.

![OCR Detection](docs/easy_erechnung_app_ocr_detection.png)

---

### Step 2: AI Post-Processing

The local AI model analyzes the OCR output and intelligently extracts all relevant invoice data.

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

## 🚀 Setup

```bash

# get the default AI model for invoice data assignment
ollama pull qwen3:30b-a3b

# Activate the Python virtual environment
source venv/bin/activate

# Install dependencies
python install.py

# Run OCR on a demo invoice
python ocr.py demo/verify.pdf demo/verify.md

# Compile the App
./compile.sh

# Start the App
./start.sh
```

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
