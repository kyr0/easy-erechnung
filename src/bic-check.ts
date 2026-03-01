import { promises as fs } from "node:fs";
import { parseCSV, createReadableStreamFromString } from "wasm-csv-parser";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Bankleitzahl;Merkmal;Bezeichnung;PLZ;Ort;Kurzbezeichnung;PAN;BIC;...

const CSV_PATH = join(
	__dirname,
	"..",
	"data",
	"bundesbank_blz-aktuell-csv-data.csv",
);

export async function lookupBIC(
	bic: string,
): Promise<{ valid: true; name: string } | { valid: false }> {
	if (!bic) return { valid: false };

	const data = await fs.readFile(CSV_PATH, "utf-8");
	const stream = createReadableStreamFromString(data);
	const needle = bic.toUpperCase();

	for await (const row of parseCSV(stream, {
		delimiter: ";",
		quote: '"',
		header: true,
	})) {
		if (row[7]?.toUpperCase() === needle) {
			return { valid: true, name: row[2] };
		}
	}

	return { valid: false };
}

// CLI entry point
if (
	import.meta.url === `file://${process.argv[1]}` ||
	process.argv[1]?.endsWith("bic-check.ts")
) {
	lookupBIC(process.argv[2] ?? "").then((result) => {
		console.log(JSON.stringify(result));
	});
}
