package de.aronhomberg;

import org.mustangproject.Invoice;
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableItem;
import org.mustangproject.ZUGFeRD.TransactionCalculator;
import org.mustangproject.ZUGFeRD.ZUGFeRDImporter;
import org.mustangproject.ZUGFeRD.ZUGFeRDInvoiceImporter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

public final class ZugferdEmbeddedReader {
    private ZugferdEmbeddedReader() {}

    public static Optional<InvoiceResponse.Invoice> tryParse(String pdfPath) {
        try {
            // High-level (positions/parties) via invoice importer
            ZUGFeRDInvoiceImporter zii = new ZUGFeRDInvoiceImporter(pdfPath);
            Invoice inv = zii.extractInvoice();
            if (inv == null) return Optional.empty();

            // Low-level helpers (IBAN/BIC/etc.) via importer
            ZUGFeRDImporter low = new ZUGFeRDImporter(pdfPath);

            InvoiceResponse.Invoice out = new InvoiceResponse.Invoice();

            out.InvoiceNumber = nullToEmpty(inv.getNumber());
            out.DocumentCurrencyCode = emptyOr(inv.getCurrency(), "EUR");

            out.InvoiceDate = dateToIso(inv.getIssueDate());
            out.DueDate     = dateToIso(inv.getDueDate());

            // Parties
            out.Seller = mapParty(inv.getSender());
            out.Buyer  = mapParty(inv.getRecipient());

            // Payment-ish (best effort)
            out.IBAN = nullToEmpty(safe(() -> low.getIBAN()));
            out.BIC  = nullToEmpty(safe(() -> low.getBIC()));
            out.BankName = nullToEmpty(safe(() -> low.getBankName()));
            out.PaymentReceiver = nullToEmpty(safe(() -> low.getHolder()));
            out.PaymentReference = nullToEmpty(safe(() -> low.getForeignReference()));

            // Lines
            List<InvoiceResponse.Invoice.InvoiceLine> lines = new ArrayList<>();
            IZUGFeRDExportableItem[] items = inv.getZFItems();
            for (int i = 0; i < items.length; i++) {
                IZUGFeRDExportableItem it = items[i];
                InvoiceResponse.Invoice.InvoiceLine l = new InvoiceResponse.Invoice.InvoiceLine();
                l.LineID = String.valueOf(i + 1);
                l.ProductName = it.getProduct() != null ? nullToEmpty(it.getProduct().getName()) : "";
                l.Unit = it.getProduct() != null ? nullToEmpty(it.getProduct().getUnit()) : "";
                l.Quantity = bd(it.getQuantity()).doubleValue();
                l.UnitPrice = bd(it.getPrice()).doubleValue();

                BigDecimal lineTotal = bd(it.getPrice()).multiply(bd(it.getQuantity()));
                l.LineTotalAmount = lineTotal.doubleValue();

                BigDecimal vat = it.getProduct() != null ? bd(it.getProduct().getVATPercent()) : BigDecimal.ZERO;
                l.TaxPercentage = vat.doubleValue();
                String taxCategoryCode = it.getProduct() != null
                        ? TaxCategory.normalize(it.getProduct().getTaxCategoryCode())
                        : "";
                l.TaxCategoryCode = taxCategoryCode.isEmpty() ? TaxCategory.fromPercentage(vat) : taxCategoryCode;

                lines.add(l);
            }
            out.InvoiceLines = lines;

            // Totals: compute from line items (TransactionCalculator methods are protected)
            BigDecimal lineTotal = BigDecimal.ZERO;
            Map<BigDecimal, BigDecimal> vatBasisByRate = new LinkedHashMap<>();
            for (InvoiceResponse.Invoice.InvoiceLine l : lines) {
                BigDecimal lt = BigDecimal.valueOf(l.LineTotalAmount);
                lineTotal = lineTotal.add(lt);
                BigDecimal rate = BigDecimal.valueOf(l.TaxPercentage);
                vatBasisByRate.merge(rate, lt, BigDecimal::add);
            }

            BigDecimal taxExclusive = lineTotal;
            BigDecimal totalTax = BigDecimal.ZERO;
            for (var entry : vatBasisByRate.entrySet()) {
                BigDecimal tax = entry.getValue().multiply(entry.getKey())
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                totalTax = totalTax.add(tax);
            }
            BigDecimal grandTotal = taxExclusive.add(totalTax);

            // Override with TransactionCalculator public methods where available
            TransactionCalculator tc = new TransactionCalculator(inv);
            BigDecimal tcGrandTotal = bd(tc.getGrandTotal());
            BigDecimal tcPayable = bd(tc.getDuePayable());
            if (tcGrandTotal.compareTo(BigDecimal.ZERO) > 0) grandTotal = tcGrandTotal;
            BigDecimal payable = tcPayable.compareTo(BigDecimal.ZERO) > 0 ? tcPayable : grandTotal;

            out.MonetarySummation = new InvoiceResponse.Invoice.MonetarySummation();
            out.MonetarySummation.LineTotal = lineTotal.setScale(2, RoundingMode.HALF_UP).doubleValue();
            out.MonetarySummation.TaxExclusiveAmount = taxExclusive.setScale(2, RoundingMode.HALF_UP).doubleValue();
            out.MonetarySummation.TaxInclusiveAmount = grandTotal.setScale(2, RoundingMode.HALF_UP).doubleValue();
            out.MonetarySummation.PayableAmount = payable.setScale(2, RoundingMode.HALF_UP).doubleValue();

            BigDecimal taxAmount = grandTotal.subtract(taxExclusive).max(BigDecimal.ZERO);

            String dominantCategory = TaxCategory.dominantCategory(lines);

            out.Tax = new InvoiceResponse.Invoice.Tax();
            out.Tax.TaxTypeCode = TaxCategory.VAT_TYPE;
            out.Tax.TaxCategoryCode = dominantCategory;
            out.Tax.TaxPercentage = TaxCategory.toPercentage(dominantCategory);
            out.Tax.TaxAmount = taxAmount.setScale(2, RoundingMode.HALF_UP).doubleValue();

            return Optional.of(out);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static InvoiceResponse.Invoice.Party mapParty(org.mustangproject.ZUGFeRD.IZUGFeRDExportableTradeParty p) {
        InvoiceResponse.Invoice.Party o = new InvoiceResponse.Invoice.Party();
        if (p == null) return o;
        o.Name = nullToEmpty(p.getName());
        o.StreetName = nullToEmpty(p.getStreet());
        o.PostalCode = nullToEmpty(p.getZIP());
        o.City = nullToEmpty(p.getLocation());
        o.CountryCode = nullToEmpty(p.getCountry());
        o.TaxVATNumber = nullToEmpty(p.getVATID());
        o.TaxIdentificationNumber = nullToEmpty(p.getTaxID());
        return o;
    }

    private static String dateToIso(Date d) {
        if (d == null) return "";
        LocalDate ld = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return ld.toString();
    }

    private static BigDecimal bd(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String emptyOr(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }
    private static <T> T safe(SupplierX<T> s) {
        try { return s.get(); } catch (Exception e) { return null; }
    }

    @FunctionalInterface
    private interface SupplierX<T> { T get() throws Exception; }
}
