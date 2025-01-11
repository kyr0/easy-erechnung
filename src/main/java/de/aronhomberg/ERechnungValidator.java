package de.aronhomberg;

import org.mustangproject.validator.ZUGFeRDValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class ERechnungValidator extends ZUGFeRDValidator {
    private static final Logger logger = LoggerFactory.getLogger(ERechnungValidator.class);

    public static class ValidationResult {
        public final boolean isValid;
        public final List<String> messages;
        public final List<String> errors;
        public final List<String> notices;

        public ValidationResult(boolean isValid, List<String> messages, List<String> errors, List<String> notices) {
            this.isValid = isValid;
            this.messages = messages;
            this.errors = errors;
            this.notices = notices;
        }
    }

    public static ValidationResult doValidate(String pdfFilePath) {
        var validator = new ERechnungValidator();
        StringBuilder log = new StringBuilder();
        validator.setLogAppend(log.toString());

        // Perform validation
        String xmlResult = validator.validate(pdfFilePath);

        return parseValidationResult(xmlResult);
    }

    private static ValidationResult parseValidationResult(String xmlResult) {
        List<String> messages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        boolean isValid = true;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlResult)));

            // Check PDF validation
            NodeList pdfSummary = doc.getElementsByTagName("pdf");
            if (pdfSummary.getLength() > 0) {
                Element pdfElement = (Element) pdfSummary.item(0);
                NodeList pdfStatusNodes = pdfElement.getElementsByTagName("summary");
                if (pdfStatusNodes.getLength() > 0) {
                    String pdfStatus = ((Element) pdfStatusNodes.item(0)).getAttribute("status");
                    if (!"valid".equals(pdfStatus)) {
                        isValid = false;
                        messages.add("PDF-Validierung fehlgeschlagen");
                    }
                }
            }

            // Parse XML validation messages
            NodeList messageNodes = doc.getElementsByTagName("messages").item(0).getChildNodes();
            for (int i = 0; i < messageNodes.getLength(); i++) {
                if (messageNodes.item(i) instanceof Element) {
                    Element messageElement = (Element) messageNodes.item(i);
                    String type = messageElement.getTagName(); // "error" or "notice"
                    String content = messageElement.getTextContent().trim();

                    String formattedMessage = content;
                    if (content.contains("]")) {
                        // Extract the actual message after the code
                        formattedMessage = content.substring(content.indexOf("]") + 1).trim();
                    }

                    if ("error".equals(type)) {
                        errors.add(formattedMessage);
                        isValid = false;
                    } else if ("notice".equals(type)) {
                        notices.add(formattedMessage);
                    }
                }
            }

            // Check final validation status
            NodeList finalSummary = doc.getElementsByTagName("summary");
            if (finalSummary.getLength() > 0) {
                Element summaryElement = (Element) finalSummary.item(finalSummary.getLength() - 1);
                if (!"valid".equals(summaryElement.getAttribute("status"))) {
                    isValid = false;
                }
            }

        } catch (Exception e) {
            logger.error("Error parsing validation result", e);
            messages.add("Fehler beim Parsen des Validierungsergebnisses: " + e.getMessage());
            isValid = false;
        }

        // Add summary messages
        if (errors.isEmpty() && notices.isEmpty()) {
            if (isValid) {
                messages.add("Die e-Rechnung ist vollständig valide.");
            } else {
                messages.add("Die e-Rechnung enthält Fehler.");
            }
        }

        return new ValidationResult(isValid, messages, errors, notices);
    }
}