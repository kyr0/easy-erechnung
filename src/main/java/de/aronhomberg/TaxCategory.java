package de.aronhomberg;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TaxCategory {
    // Peppol aligned tax category codes (UNCL 5305 subset). Official labels:
    // S = "Standard rate", AA = "Lower rate", AE = "VAT Reverse Charge",
    // K = "VAT exempt for EEA intra-community supply", Z = "Zero rated goods",
    // G = "Free export item, tax not charged".
    // Reference: https://docs.peppol.eu/poac/eu/pint-eu/trn-invoice/codelist/Aligned-TaxCategoryCodes/
    static final String STANDARD = "S";
    static final String LOWER_RATE = "AA";
    static final String REVERSE_CHARGE = "AE";
    static final String INTRA_COMMUNITY = "K";
    static final String ZERO_RATED = "Z";
    static final String FREE_EXPORT = "G";
    static final String VAT_TYPE = "VAT";

    private TaxCategory() {}

    static String normalize(String taxCategoryCode) {
        if (taxCategoryCode == null) {
            return "";
        }
        String normalized = taxCategoryCode.trim().toUpperCase(Locale.ROOT);
        // Why map Z -> G?
        // The official Peppol list distinguishes Z ("Zero rated goods") from
        // G ("Free export item, tax not charged"). We still collapse Z to G because the current
        // desktop UI exposes only one generic 0% bucket and persists that bucket as G. This keeps
        // imported embedded XML and older app data round-trippable through the existing UI model.
        return normalized.equals(ZERO_RATED) ? FREE_EXPORT : normalized;
    }

    static String defaultForMissingSemanticCode(BigDecimal taxPercentage) {
        // Only used when the caller gave us a percentage but no semantic category code.
        // The current app model has a single generic 0% bucket and persists it as G, so this
        // fallback preserves existing app behavior instead of guessing between G, Z, AE or K.
        return fromPercentage(taxPercentage);
    }

    static String fromPercentage(BigDecimal taxPercentage) {
        BigDecimal percentage = taxPercentage == null ? BigDecimal.ZERO : taxPercentage.stripTrailingZeros();
        if (percentage.compareTo(BigDecimal.ZERO) == 0) {
            // A numeric 0% rate alone is not enough to distinguish AE, K, Z and G.
            // If only the percentage is known, we fall back to the app's generic 0% bucket.
            return FREE_EXPORT;
        }
        if (percentage.compareTo(new BigDecimal("7")) == 0) {
            return LOWER_RATE;
        }
        return STANDARD;
    }

    static String fromPercentage(double taxPercentage) {
        return fromPercentage(BigDecimal.valueOf(taxPercentage));
    }

    static double toPercentage(String taxCategoryCode) {
        // Reverse charge remains under the VAT tax scheme, but its amount/rate contribution is 0.
        // Peppol BR-AE-09 says the VAT category tax amount for reverse charge "shall be 0 (zero)":
        // https://docs.peppol.eu/poac/eu/pint-eu/trn-invoice/rule/BR-AE-09/
        return switch (normalize(taxCategoryCode)) {
            case STANDARD -> 19.0;
            case LOWER_RATE -> 7.0;
            default -> 0.0;
        };
    }

    /**
     * Chooses a single invoice-level tax category from the line-level categories.
     *
     * The app's current invoice model stores one top-level {@code invoice.Tax} object even though
     * real EN 16931 / Peppol invoices can contain multiple VAT breakdown rows. To keep that legacy
     * model internally consistent, we choose the tax category with the largest aggregated net line
     * basis and use it as the representative top-level category.
     *
     * This is an application fallback for the simplified UI/data model, not an EN 16931 rule.
     * Line-level categories remain the authoritative source when multiple tax categories occur.
     */
    static String dominantCategory(List<InvoiceResponse.Invoice.InvoiceLine> invoiceLines) {
        Map<String, BigDecimal> basisByCategory = new LinkedHashMap<>();
        for (InvoiceResponse.Invoice.InvoiceLine line : invoiceLines) {
            if (line == null) {
                continue;
            }
            String taxCategoryCode = normalize(line.TaxCategoryCode);
            if (taxCategoryCode.isEmpty()) {
                taxCategoryCode = fromPercentage(line.TaxPercentage);
            }
            basisByCategory.merge(taxCategoryCode, BigDecimal.valueOf(line.LineTotalAmount), BigDecimal::add);
        }

        BigDecimal dominantBasis = BigDecimal.ZERO;
        String dominantCategory = FREE_EXPORT;
        for (var entry : basisByCategory.entrySet()) {
            if (entry.getValue().compareTo(dominantBasis) > 0) {
                dominantBasis = entry.getValue();
                dominantCategory = entry.getKey();
            }
        }
        return dominantCategory;
    }
}
