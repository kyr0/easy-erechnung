package de.aronhomberg;
import org.mustangproject.ZUGFeRD.IZUGFeRDAllowanceCharge;
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableItem;
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableProduct;

import java.math.BigDecimal;

public class Item implements IZUGFeRDExportableItem {
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final IZUGFeRDExportableProduct product;

    public Item(BigDecimal price, BigDecimal quantity, IZUGFeRDExportableProduct product) {
        this.price = price;
        this.quantity = quantity;
        this.product = product;
    }

    @Override
    public BigDecimal getPrice() {
        return price;
    }

    @Override
    public BigDecimal getQuantity() {
        return quantity;
    }

    @Override
    public IZUGFeRDExportableProduct getProduct() {
        return product;
    }

    @Override
    public IZUGFeRDAllowanceCharge[] getItemAllowances() {
        return null;
    }

    @Override
    public IZUGFeRDAllowanceCharge[] getItemCharges() {
        return null;
    }
}