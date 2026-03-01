package de.aronhomberg;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import org.mustangproject.ZUGFeRD.*;

public class ZUGFeRDInvoiceWriter implements IExportableTransaction {
    private final InvoiceResponse.Invoice invoice;
    private final String inputPdfPath;
    private final String outputPdfPath;

    public ZUGFeRDInvoiceWriter(InvoiceResponse.Invoice invoice, String inputPdfPath) {
        this.invoice = invoice;
        this.inputPdfPath = inputPdfPath;
        this.outputPdfPath = inputPdfPath.replace(".pdf", ".erechnung.pdf");
    }

    public String generateZUGFeRDInvoice() {
        try {
            System.out.println("Reading original PDF: " + inputPdfPath);
            IZUGFeRDExporter ze = new ZUGFeRDExporterFromA1()
                    .setProducer("e@sy e-Rechnung")
                    .setCreator(System.getProperty("user.name"))
                    .load(inputPdfPath);

            System.out.println("Generating and attaching ZUGFeRD data...");
            ze.setTransaction(this);

            System.out.println("Writing ZUGFeRD PDF: " + outputPdfPath);
            ze.export(outputPdfPath);

            System.out.println("ZUGFeRD invoice generated successfully at: " + outputPdfPath);

            ze.close();
            return outputPdfPath;
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to generate ZUGFeRD invoice.");
        }
        return null;
    }

    @Override
    public String getCurrency() {
        return invoice.DocumentCurrencyCode;
    }

    @Override
    public Date getDeliveryDate() {
        return null;
    }

    @Override
    public Date getDueDate() {
        try {
            // Parse ISO date (YYYY-MM-DD) to LocalDate
            LocalDate localDate = LocalDate.parse(invoice.DueDate);
            // Convert to Date at start of day in system timezone
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            System.err.println("Error parsing due date: " + invoice.DueDate);
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public Date getIssueDate() {
        try {
            // Parse ISO date (YYYY-MM-DD) to LocalDate
            LocalDate localDate = LocalDate.parse(invoice.InvoiceDate);
            // Convert to Date at start of day in system timezone
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            System.err.println("Error parsing issue date: " + invoice.InvoiceDate);
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String getNumber() {
        return invoice.InvoiceNumber;
    }


    @Override
    public String getOwnOrganisationName() {
        return invoice.Seller.Name;
    }

    @Override
    public String getOwnStreet() {
        return invoice.Seller.StreetName;
    }

    @Override
    public String getOwnZIP() {
        return invoice.Seller.PostalCode;
    }

    @Override
    public String getOwnLocation() {
        return invoice.Seller.City;
    }

    @Override
    public String getOwnCountry() {
        return invoice.Seller.CountryCode;
    }

    @Override
    public String getOwnVATID() {
        return invoice.Seller.TaxIdentificationNumber;
    }

    @Override
    public IZUGFeRDExportableTradeParty getRecipient() {
        return new IZUGFeRDExportableTradeParty() {
            @Override
            public String getName() {
                return invoice.Buyer.Name;
            }

            @Override
            public String getStreet() {
                return invoice.Buyer.StreetName;
            }

            @Override
            public String getZIP() {
                return invoice.Buyer.PostalCode;
            }

            @Override
            public String getLocation() {
                return invoice.Buyer.City;
            }

            @Override
            public String getCountry() {
                return invoice.Buyer.CountryCode;
            }

            @Override
            public String getVATID() {
                return this.getCountry() + invoice.Buyer.TaxVATNumber;
            }

            @Override
            public String getTaxID() {
                return invoice.Buyer.TaxIdentificationNumber;
            }
        };
    }

    @Override
    public IZUGFeRDExportableTradeParty getSender() {
        return new IZUGFeRDExportableTradeParty() {
            @Override
            public String getName() {
                return invoice.Seller.Name;
            }

            @Override
            public String getStreet() {
                return invoice.Seller.StreetName;
            }

            @Override
            public String getZIP() {
                return invoice.Seller.PostalCode;
            }

            @Override
            public String getLocation() {
                return invoice.Seller.City;
            }

            @Override
            public String getCountry() {
                return invoice.Seller.CountryCode;
            }

            @Override
            public String getVATID() {
                return this.getCountry() + invoice.Seller.TaxVATNumber;
            }

            @Override
            public String getTaxID() {
                return invoice.Seller.TaxIdentificationNumber;
            }
        };
    }

    @Override
    public IZUGFeRDExportableItem[] getZFItems() {
        List<InvoiceResponse.Invoice.InvoiceLine> lines = invoice.InvoiceLines;
        IZUGFeRDExportableItem[] items = new IZUGFeRDExportableItem[lines.size()];

        for (int i = 0; i < lines.size(); i++) {
            InvoiceResponse.Invoice.InvoiceLine line = lines.get(i);

            String unit = (line.Unit != null && !line.Unit.isBlank()) ? line.Unit.trim() : "C62";

            items[i] = new Item(
                    BigDecimal.valueOf(line.UnitPrice),
                    BigDecimal.valueOf(line.Quantity),
                    new Product(line.ProductName, line.ProductName, unit, BigDecimal.valueOf(line.TaxPercentage))
            );
        }
        return items;
    }

    // Unused methods returning null for simplicity
    @Override
    public String getOwnOrganisationFullPlaintextInfo() {
        return null;
    }

    @Override
    public String getReferenceNumber() {
        return invoice.PaymentMeans.PaymentInformation.PaymentReference;
    }

    @Override
    public IZUGFeRDAllowanceCharge[] getZFAllowances() {
        return null;
    }

    @Override
    public IZUGFeRDAllowanceCharge[] getZFCharges() {
        return null;
    }

    @Override
    public IZUGFeRDAllowanceCharge[] getZFLogisticsServiceCharges() {
        return null;
    }

    @Override
    public String getPaymentTermDescription() {
        return "Payment due by " + invoice.DueDate;
    }
}
