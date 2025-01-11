package de.aronhomberg;

import org.mustangproject.ZUGFeRD.IZUGFeRDExportableProduct;
import java.math.BigDecimal;

public class Product implements IZUGFeRDExportableProduct {
    private final String description;
    private final String name;
    private final String unit;
    private final BigDecimal VATPercent;

    public Product(String description, String name, String unit, BigDecimal VATPercent) {
        this.description = description;
        this.name = name;
        this.unit = unit;
        this.VATPercent = VATPercent;
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
}