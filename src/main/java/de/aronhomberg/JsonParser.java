package de.aronhomberg;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonParser {
    public static InvoiceResponse parseInvoiceResponse(String json) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.readValue(json, InvoiceResponse.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}