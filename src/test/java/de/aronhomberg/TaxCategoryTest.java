package de.aronhomberg;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaxCategoryTest {

    @Test
    void normalizesZeroRatedGoodsToTheAppsGenericZeroPercentBucket() {
        assertEquals("G", TaxCategory.normalize("Z"));
    }

    @Test
    void picksDominantCategoryByAggregatedNetBasis() {
        InvoiceResponse.Invoice.InvoiceLine firstReverseChargeLine = new InvoiceResponse.Invoice.InvoiceLine();
        firstReverseChargeLine.TaxCategoryCode = "AE";
        firstReverseChargeLine.LineTotalAmount = 60.0;

        InvoiceResponse.Invoice.InvoiceLine secondReverseChargeLine = new InvoiceResponse.Invoice.InvoiceLine();
        secondReverseChargeLine.TaxCategoryCode = "AE";
        secondReverseChargeLine.LineTotalAmount = 50.0;

        InvoiceResponse.Invoice.InvoiceLine standardRateLine = new InvoiceResponse.Invoice.InvoiceLine();
        standardRateLine.TaxCategoryCode = "S";
        standardRateLine.LineTotalAmount = 100.0;

        assertEquals("AE", TaxCategory.dominantCategory(List.of(
                firstReverseChargeLine,
                secondReverseChargeLine,
                standardRateLine)));
    }
}
