package de.aronhomberg;

import org.junit.jupiter.api.Test;
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableItem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZUGFeRDInvoiceWriterTest {

    @Test
    void preservesReverseChargeTaxCategoryOnExportedItems() {
        InvoiceResponse.Invoice invoice = new InvoiceResponse.Invoice();
        InvoiceResponse.Invoice.InvoiceLine line = new InvoiceResponse.Invoice.InvoiceLine();
        line.ProductName = "Beratung";
        line.Quantity = 1.0;
        line.UnitPrice = 100.0;
        line.TaxCategoryCode = "AE";
        line.TaxPercentage = 0.0;
        line.Unit = "HUR";
        invoice.InvoiceLines = List.of(line);

        ZUGFeRDInvoiceWriter writer = new ZUGFeRDInvoiceWriter(invoice, "sample.pdf");
        IZUGFeRDExportableItem[] items = writer.getZFItems();

        assertEquals(1, items.length);
        assertEquals("AE", items[0].getProduct().getTaxCategoryCode());
        assertTrue(items[0].getProduct().isReverseCharge());
        assertEquals(0.0, items[0].getProduct().getVATPercent().doubleValue());
    }
}
