package de.aronhomberg;

import java.math.BigDecimal;
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableProduct;

public class Product implements IZUGFeRDExportableProduct {
    private final String description;
    private final String name;
    private final String unit;
    private final BigDecimal VATPercent;
    private final String taxCategoryCode;

    public Product(String description, String name, String unit, BigDecimal VATPercent) {
        this(description, name, unit, VATPercent, TaxCategory.defaultForMissingSemanticCode(VATPercent));
    }

    public Product(String description, String name, String unit, BigDecimal VATPercent, String taxCategoryCode) {
        this.description = description;
        this.name = name;
        this.unit = unit;
        this.VATPercent = VATPercent;
        this.taxCategoryCode = TaxCategory.normalize(taxCategoryCode);
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUnit() {
        return unit;
    }

    @Override
    public BigDecimal getVATPercent() {
        return VATPercent;
    }

    @Override
    public boolean isReverseCharge() {
        return TaxCategory.REVERSE_CHARGE.equals(taxCategoryCode);
    }

    @Override
    public boolean isIntraCommunitySupply() {
        return TaxCategory.INTRA_COMMUNITY.equals(taxCategoryCode);
    }

    @Override
    public String getTaxCategoryCode() {
        return taxCategoryCode;
    }
}
