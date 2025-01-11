package de.aronhomberg;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InvoiceResponse {
    @JsonProperty("Invoice")
    public Invoice invoice;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Invoice {
        public String InvoiceNumber;
        public String InvoiceDate;
        public String DueDate;
        public Party Seller;
        public Party Buyer;
        public String DocumentCurrencyCode;
        public PaymentMeans PaymentMeans;
        public Tax Tax;
        public MonetarySummation MonetarySummation;
        public List<InvoiceLine> InvoiceLines;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Party {
            public String Name;
            public String StreetName;
            public String City;
            public String PostalCode;
            public String CountryCode;
            public String TaxIdentificationNumber;
            public String TaxVATNumber;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class PaymentMeans {
            public String Type;
            public PaymentInformation PaymentInformation;

            public static class PaymentInformation {
                public String IBAN;
                public String BIC;
                public String PaymentReference;
                public String PaymentReceiver;
                public String BankName;
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Tax {
            public String TaxTypeCode;
            public String TaxCategoryCode;
            public double TaxPercentage;
            public double TaxAmount;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class MonetarySummation {
            public double LineTotal;
            public double TaxExclusiveAmount;
            public double TaxInclusiveAmount;
            public double PayableAmount;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class InvoiceLine {
            public String LineID;
            public String ProductName;
            public double Quantity;
            public double UnitPrice;
            public double LineTotalAmount;
            public String TaxCategoryCode;
            public double TaxPercentage;
            public String Unit;
        }
    }
}