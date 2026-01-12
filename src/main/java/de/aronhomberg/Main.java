package de.aronhomberg;

import com.formdev.flatlaf.FlatLightLaf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jdesktop.swingx.JXTable;
import org.jdesktop.swingx.JXDatePicker;

import javax.swing.event.TableModelEvent;
import javax.swing.text.DefaultFormatterFactory;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.text.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.NumberFormatter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.prefs.Preferences;
import java.util.concurrent.TimeUnit;

import okhttp3.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;

public class Main {
    private static final Preferences prefs = Preferences.userNodeForPackage(Main.class);

    private static final String EXTRACTION_PROMPT = """
            You are an expert OCR data analyst and accountant. Given the following information (PPOCR JSON and Markdown), construct a JSON that optimally has all fields to describe a ZUGFeRD invoice.\s
            The OCRed document is an invoice that is to be sent to a company whom I worked for.\s
            Return only JSON (to be parsed as JSON directly, without any Markdown formatting).\s
            The response data format MUST match. Do not add or remove any fields.\s
            Additional rules:\s
            - Analyze the type of unit per invoice position. It can be either: HUR, DAY or PCE\s
              - HUR: per hour, DAY: per day (often referred to as PT, MT), PCE: per unit\s

            Seller address:\s
            ${SELLER_ADDRESS}

            Seller Steuernummer: ${SELLER_TAX_NO}

            PPOCRed JSON data:
            ${PPOCR_RESULT}

            OCRed Markdown data:
            ${MD_RESULT}

            JSON response format (MUST match!, MUST NOT include Markdown formatting. This will be parsed!):\s
            {
              "Invoice": {
                "InvoiceNumber": "INV-20250108-001",
                "InvoiceDate": "2025-01-08",
                "DueDate": "2025-01-22",
                "Seller": {
                  "Name": "Example Seller GmbH",
                  "StreetName": "Musterstraße 1",
                  "City": "Musterstadt",
                  "PostalCode": "12345",
                  "CountryCode": "DE",
                  "TaxIdentificationNumber": "DE123456789"
                },
                "Buyer": {
                  "Name": "Example Buyer GmbH",
                  "StreetName": "Käuferstraße 2",
                  "City": "Käuferstadt",
                  "PostalCode": "54321",
                  "CountryCode": "DE",
                  "TaxIdentificationNumber": "DE987654321"
                },
                "DocumentCurrencyCode": "EUR",
                "PaymentMeans": {
                  "Type": "42",
                  "PaymentInformation": {
                    "PaymentReceiver": "Max Mustermann",
                    "IBAN": "DE89370400440532013000",
                    "BIC": "COBADEFFXXX",
                    "BankName: "Sparkasse Freising",
                    "PaymentReference": "INV-20250108-001"
                  }
                },
                "Tax": {
                  "TaxTypeCode": "VAT",
                  "TaxCategoryCode": "S",
                  "TaxPercentage": 19.00,
                  "TaxAmount": 9.50
                },
                "MonetarySummation": {
                  "LineTotal": 50.00,
                  "TaxExclusiveAmount": 50.00,
                  "TaxInclusiveAmount": 59.50,
                  "PayableAmount": 59.50
                },
                "InvoiceLines": [
                  {
                    "LineID": "1",
                    "ProductName": "Example Product",
                    "Unit": "PCE",
                    "Quantity": 1.0,
                    "UnitPrice": 50.00,
                    "LineTotalAmount": 50.00,
                    "TaxCategoryCode": "S",
                    "TaxPercentage": 19.00
                  }
                ]
              }
            }
                """;

    private static final String TITLE = "e@sy e-Rechnung by Aron Homberg";
    private static final String DRAG_DROP_LABEL = "PDF-Rechnung hier ablegen";
    private static final String FILE_ACCEPTED_MSG = "Datei akzeptiert: ";
    private static final String INVALID_FILE_MSG = "Falscher Dateityp. Bitte PDF-Datei auswählen.";
    private static final String ERROR_MSG = "Fehler beim Verarbeiten der Datei.";

    private static final Map<String, JTextField> senderFieldsMap = new HashMap<>();
    private static final Map<String, JTextField> recipientFieldsMap = new HashMap<>();
    private static final Map<String, JComponent> invoiceDetailsMap = new LinkedHashMap<>();
    private static final Map<String, JComponent> einstellungenFieldsMap = new LinkedHashMap<>();
    private static final Map<String, JTextField> summenUndSteuernFieldsMap = new LinkedHashMap<>();
    private static DefaultTableModel positionenTableModel;
    private static String pdfFilePath;

    private static final Map<String, String> UNIT_TRANSLATIONS = Map.of(
            "PCE", "Stück (PCE)",
            "HUR", "Stunden (HUR)",
            "DAY", "Tage (DAY)");

    private static final Map<String, String> TAX_TRANSLATIONS = Map.of(
            "S", "19% (Normal, S)",
            "AA", "7% (Ermäßigt, AA)",
            "E", "Befreit (E)",
            "AE", "Steuerumkehr (AE)",
            "K", "Innergemeinschaftliche Lieferung (K)",
            "G", "0% (Nullsteuersatz, G)");

    public static void main(String[] args) {
        // Set Look and Feel
        setLookAndFeel();

        // Create and configure the main frame
        JFrame frame = createMainFrame();

        // Create and add the status bar
        JLabel statusBar = createStatusBar();

        // Create and add the drag-and-drop panel
        JPanel dragDropPanel = createDragAndDropPanel();
        JLabel dragDropLabel = createMessageLabel(dragDropPanel);

        // Create tabbed panel
        JTabbedPane tabbedPane = createTabbedPanel();

        // Create split pane
        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                dragDropPanel, // Left side
                tabbedPane // Right side
        );
        splitPane.setDividerLocation(1024); // Initial divider position

        configureDragAndDrop(dragDropPanel, dragDropLabel, frame, statusBar, splitPane);

        // Add components to the frame
        frame.add(splitPane, BorderLayout.CENTER);
        frame.add(statusBar, BorderLayout.SOUTH);

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("Datei");
        JMenuItem createERechnungItem = new JMenuItem("e-Rechnung erstellen");

        createERechnungItem.addActionListener(e -> createERechnung());

        fileMenu.add(createERechnungItem);
        menuBar.add(fileMenu);
        frame.setJMenuBar(menuBar);

        // Show the frame
        frame.setVisible(true);
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Exception ex) {
            System.err.println("Failed to initialize FlatLaf");
        }
    }

    private static JFrame createMainFrame() {
        JFrame frame = new JFrame(TITLE);
        frame.setSize(1024, 900);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        return frame;
    }

    private static JPanel createDragAndDropPanel() {
        JPanel dragDropPanel = new JPanel(new BorderLayout());
        dragDropPanel.setBackground(Color.WHITE);
        return dragDropPanel;
    }

    private static JLabel createMessageLabel(JPanel dragDropPanel) {
        JLabel messageLabel = new JLabel(DRAG_DROP_LABEL, SwingConstants.CENTER);
        messageLabel.setFont(new Font("Arial", Font.PLAIN, 16));
        dragDropPanel.add(messageLabel, BorderLayout.CENTER);
        return messageLabel;
    }

    private static JLabel createStatusBar() {
        JLabel statusBar = new JLabel("Bereit", SwingConstants.LEFT);
        statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusBar.setOpaque(true);
        statusBar.setBackground(Color.LIGHT_GRAY);
        statusBar.setFont(new Font("Arial", Font.PLAIN, 14));
        return statusBar;
    }

    private static JTabbedPane createTabbedPanel() {
        JTabbedPane tabbedPane = new JTabbedPane();

        // Add sample tabs
        JPanel rahmendatenTab = createRahmendatenTab();
        JPanel positionenTab = createPositionenTab();
        JPanel summenUndSteuernTab = createSummenUndSteuernTab();
        JPanel einstellungenTab = createEinstellungenTab();

        tabbedPane.addTab("Rahmendaten", rahmendatenTab);
        tabbedPane.addTab("Positionen", positionenTab);
        tabbedPane.addTab("Summen und Steuern", summenUndSteuernTab);
        tabbedPane.addTab("Einstellungen", einstellungenTab);

        loadEinstellungen();

        return tabbedPane;
    }

    private static void initializeFieldMap(Map<String, JTextField> fieldMap, String[] fields) {
        for (String field : fields) {
            fieldMap.putIfAbsent(field, new JTextField(""));
        }
    }

    private static JPanel createRahmendatenTab() {
        // Initialize Sender and Recipient fields
        initializeFieldMap(senderFieldsMap, new String[] {
                "Name", "Adresse", "PLZ", "Ort", "Land", "Steuernummer/Ust-ID"
        });
        initializeFieldMap(recipientFieldsMap, new String[] {
                "Name", "Adresse", "PLZ", "Ort", "Land", "Steuernummer/Ust-ID"
        });

        // Initialize Invoice Details fields
        invoiceDetailsMap.put("Zahlungsziel", new JXDatePicker());
        invoiceDetailsMap.put("Rechnungsnummer", new JTextField(""));
        invoiceDetailsMap.put("Rechnungsdatum", new JXDatePicker());
        invoiceDetailsMap.put("Währung", new JTextField("EUR"));
        invoiceDetailsMap.put("IBAN", new JTextField(""));
        invoiceDetailsMap.put("BIC", new JTextField(""));
        invoiceDetailsMap.put("Bank Name", new JTextField(""));
        invoiceDetailsMap.put("Zahlungsempfänger", new JTextField(""));
        invoiceDetailsMap.put("Zahlungsreferenz", new JTextField(""));

        // Create main panel with GridBagLayout
        JPanel rahmendatenTab = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 10, 5, 10);

        // Create Sender Panel
        JPanel senderPanel = createGroupPanel(
                "Rechnung von:",
                senderFieldsMap,
                List.of("Name", "Adresse", "PLZ", "Ort", "Land", "Steuernummer/Ust-ID"));

        // Create Recipient Panel
        JPanel recipientPanel = createGroupPanel(
                "Rechnung an:",
                recipientFieldsMap,
                List.of("Name", "Adresse", "PLZ", "Ort", "Land", "Steuernummer/Ust-ID"));

        // Create Invoice Details Panel
        JPanel invoiceDetailsPanel = createGroupPanel(
                "Rechnungsdetails:",
                invoiceDetailsMap,
                List.of("Zahlungsziel", "Rechnungsnummer", "Rechnungsdatum", "IBAN", "BIC", "Bank Name",
                        "Zahlungsempfänger", "Zahlungsreferenz"));

        gbc.gridy = 0;
        gbc.insets = new Insets(5, 10, 5, 10); // Restore default insets
        rahmendatenTab.add(invoiceDetailsPanel, gbc);

        // Add panels to the main tab panel
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        rahmendatenTab.add(senderPanel, gbc);

        gbc.gridy = 2;
        gbc.insets = new Insets(0, 10, 5, 10); // Reduce top margin for second group
        rahmendatenTab.add(recipientPanel, gbc);

        // Add a spacer to push content to the top
        gbc.gridy = 4;
        gbc.weighty = 1.0; // Spacer to occupy remaining vertical space
        rahmendatenTab.add(Box.createVerticalGlue(), gbc);

        // Set padding and alignment
        rahmendatenTab.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        return rahmendatenTab;
    }

    private static boolean validateRahmendaten() {
        // Check sender fields
        boolean hasSteuernummer = !senderFieldsMap.get("Steuernummer/Ust-ID").getText().trim().isEmpty();
        boolean hasUstId = !((JTextField) einstellungenFieldsMap.get("USt-ID")).getText().trim().isEmpty();

        if (!hasSteuernummer && !hasUstId) {
            showError("Entweder Steuernummer oder USt-ID muss angegeben werden.");
            return false;
        }

        // Check all other mandatory fields
        for (Map.Entry<String, JTextField> entry : senderFieldsMap.entrySet()) {
            if (!entry.getKey().equals("Steuernummer/Ust-ID") &&
                    entry.getValue().getText().trim().isEmpty()) {
                showError("Pflichtfeld nicht ausgefüllt: " + entry.getKey());
                return false;
            }
        }

        return true;
    }

    private static boolean validatePositionen() {
        if (positionenTableModel.getRowCount() == 0) {
            showError("Mindestens eine Position muss eingegeben werden.");
            return false;
        }
        return true;
    }

    private static boolean validateSummenUndSteuern() {
        for (Map.Entry<String, JTextField> entry : summenUndSteuernFieldsMap.entrySet()) {
            if (entry.getValue().getText().trim().isEmpty()) {
                showError("Summen und Steuern müssen vollständig ausgefüllt sein.");
                return false;
            }
        }
        return true;
    }

    private static void showError(String message) {
        JOptionPane.showMessageDialog(null, message, "Validierungsfehler",
                JOptionPane.ERROR_MESSAGE);
    }

    private static boolean validateAll() {
        if (!validateRahmendaten())
            return false;
        if (!validatePositionen())
            return false;
        if (!validateSummenUndSteuern())
            return false;
        return true;
    }

    private static void createERechnung() {
        try {
            if (!validateAll()) {
                return;
            }

            InvoiceResponse.Invoice invoice = collectFormData();
            ZUGFeRDInvoiceWriter writer = new ZUGFeRDInvoiceWriter(invoice, pdfFilePath);
            String eRechnungFilePath = writer.generateZUGFeRDInvoice();

            ERechnungValidator.ValidationResult validationResult = ERechnungValidator.doValidate(eRechnungFilePath);

            StringBuilder messageBuilder = new StringBuilder();

            if (validationResult.isValid) {
                messageBuilder.append("e-Rechnung wurde erfolgreich erstellt.\n\n");
            } else {
                messageBuilder.append("Die e-Rechnung enthält Probleme:\n\n");
            }

            if (!validationResult.errors.isEmpty()) {
                messageBuilder.append("Fehler:\n");
                for (String error : validationResult.errors) {
                    messageBuilder.append("• ").append(error).append("\n");
                }
                messageBuilder.append("\n");
            }

            if (!validationResult.notices.isEmpty()) {
                /*
                 * messageBuilder.append("Hinweise:\n");
                 * for (String notice : validationResult.notices) {
                 * messageBuilder.append("• ").append(notice).append("\n");
                 * }
                 */
            }

            JOptionPane.showMessageDialog(null,
                    messageBuilder.toString(),
                    validationResult.isValid ? "Erfolg" : "Validierungsfehler",
                    validationResult.isValid ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);

            if (validationResult.isValid) {
                try {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI("https://www.elster.de/eportal/e-rechnung"));
                } catch (Exception e) {
                    System.err.println("Failed to open Elster URL: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();

            JOptionPane.showMessageDialog(null,
                    "Fehler beim Erstellen der e-Rechnung: " + e.getMessage(),
                    "Fehler",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static JPanel createGroupPanel(String title, Map<String, ? extends JComponent> fieldMap,
            List<String> fieldOrder) {
        JPanel groupPanel = new JPanel(new GridBagLayout());
        groupPanel.setBorder(BorderFactory.createTitledBorder(title)); // Add titled border

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        int row = 0;
        for (String key : fieldOrder) {
            // Add Label
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            groupPanel.add(new JLabel(key + ":"), gbc);

            // Add Field
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            JComponent field = fieldMap.get(key);
            if (field != null) {
                groupPanel.add(field, gbc);
            }

            row++;
        }
        return groupPanel;
    }

    public static void setSenderData(Map<String, String> senderData) {
        senderData.forEach((key, value) -> {
            JTextField field = senderFieldsMap.get(key);
            if (field != null) {
                field.setText(value);
            }
        });
    }

    public static void setRecipientData(Map<String, String> recipientData) {
        recipientData.forEach((key, value) -> {
            JTextField field = recipientFieldsMap.get(key);
            if (field != null) {
                field.setText(value);
            }
        });
    }

    private static JPanel createPositionenTab() {
        // Mapping von Werten zu übersetzten Labels
        Map<String, String> unitTranslations = new HashMap<>();
        unitTranslations.put("PCE", "Stück (PCE)");
        unitTranslations.put("HUR", "Stunden (HUR)");
        unitTranslations.put("DAY", "Tage (DAY)");

        Map<String, String> taxTranslations = new HashMap<>();
        taxTranslations.put("S", "19% (Normal, S)");
        taxTranslations.put("AA", "7% (Ermäßigt, AA)");
        taxTranslations.put("E", "Befreit (E)");
        taxTranslations.put("AE", "Steuerumkehr (AE)");
        taxTranslations.put("K", "Innergemeinschaftliche Lieferung (K)");
        taxTranslations.put("G", "0% (Nullsteuersatz, G)");

        // Create a panel with BorderLayout
        JPanel positionenTab = new JPanel(new BorderLayout());

        // Define the mandatory columns for ZUGFeRD Rechnung positions
        String[] columns = {
                "Pos. Nr.", "Beschreibung", "Menge", "Einheit", "Einzelpreis", "Gesamtpreis", "Steuerklasse"
        };

        // Create a table model with editable cells and type checking
        positionenTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 2 || columnIndex == 4 || columnIndex == 5) {
                    return Double.class;
                }
                return String.class;
            }
        };

        // Add example data (optional)
        // positionenTableModel.addRow(new Object[]{"1", "Dienstleistung A", 8.00,
        // "HUR", 50.00, 400.00, "S"});
        // positionenTableModel.addRow(new Object[]{"2", "Beratung", 1.00, "DAY",
        // 800.00, 800.00, "E"});

        // Create the JXTable
        JXTable table = new JXTable(positionenTableModel);

        // Create decimal formatter
        NumberFormat germanFormat = NumberFormat.getNumberInstance(Locale.GERMANY);
        germanFormat.setMinimumFractionDigits(2);
        germanFormat.setMaximumFractionDigits(2);

        // Create decimal renderer
        DefaultTableCellRenderer decimalRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                if (value instanceof Number) {
                    value = germanFormat.format(((Number) value).doubleValue());
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        };
        decimalRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        // Create decimal editor
        DefaultCellEditor decimalEditor = new DefaultCellEditor(new JTextField()) {
            private final JTextField textField = (JTextField) getComponent();

            @Override
            public boolean stopCellEditing() {
                try {
                    String value = textField.getText();
                    if (!value.trim().isEmpty()) {
                        Number number = germanFormat.parse(value);
                        textField.setText(germanFormat.format(number));
                    }
                    return super.stopCellEditing();
                } catch (ParseException e) {
                    return false;
                }
            }

            @Override
            public Component getTableCellEditorComponent(JTable table, Object value,
                    boolean isSelected, int row, int column) {
                if (value instanceof Number) {
                    value = germanFormat.format(value);
                }
                return super.getTableCellEditorComponent(table, value, isSelected, row, column);
            }
        };

        // Set preferred widths for columns
        table.getColumnModel().getColumn(0).setPreferredWidth(50); // Pos. Nr.
        table.getColumnModel().getColumn(1).setPreferredWidth(300); // Beschreibung
        table.getColumnModel().getColumn(2).setPreferredWidth(70); // Menge
        table.getColumnModel().getColumn(3).setPreferredWidth(100); // Einheit
        table.getColumnModel().getColumn(4).setPreferredWidth(100); // Einzelpreis
        table.getColumnModel().getColumn(5).setPreferredWidth(100); // Gesamtpreis
        table.getColumnModel().getColumn(6).setPreferredWidth(150); // Steuerklasse

        // Apply decimal formatting to numeric columns
        table.getColumnModel().getColumn(2).setCellRenderer(decimalRenderer); // Menge
        table.getColumnModel().getColumn(4).setCellRenderer(decimalRenderer); // Einzelpreis
        table.getColumnModel().getColumn(5).setCellRenderer(decimalRenderer); // Gesamtpreis

        table.getColumnModel().getColumn(2).setCellEditor(decimalEditor); // Menge
        table.getColumnModel().getColumn(4).setCellEditor(decimalEditor); // Einzelpreis
        table.getColumnModel().getColumn(5).setCellEditor(decimalEditor); // Gesamtpreis

        // Einheit - Dropdown (ComboBox) mit übersetzten Labels
        JComboBox<String> unitComboBox = new JComboBox<>(unitTranslations.values().toArray(new String[0]));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(unitComboBox));

        // Steuerklasse - Dropdown (ComboBox) mit übersetzten Labels
        JComboBox<String> taxComboBox = new JComboBox<>(taxTranslations.values().toArray(new String[0]));
        table.getColumnModel().getColumn(6).setCellEditor(new DefaultCellEditor(taxComboBox));

        // Add automatic calculation of total amount
        table.getModel().addTableModelListener(e -> {
            if (e.getType() == TableModelEvent.UPDATE) {
                int row = e.getFirstRow();
                int column = e.getColumn();
                if (column == 2 || column == 4) { // Menge or Einzelpreis changed
                    try {
                        Double menge = Double.parseDouble(table.getValueAt(row, 2).toString().replace(",", "."));
                        Double einzelpreis = Double.parseDouble(table.getValueAt(row, 4).toString().replace(",", "."));
                        Double gesamtpreis = menge * einzelpreis;
                        table.setValueAt(gesamtpreis, row, 5);
                    } catch (NumberFormatException | NullPointerException ex) {
                        // Handle invalid number format
                    }
                }
            }
        });

        // Enable horizontal scrolling for larger content
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Add the table to a scroll pane
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Add scroll pane to the panel
        positionenTab.add(scrollPane, BorderLayout.CENTER);

        // Add a button panel for adding/removing rows
        JPanel buttonPanel = new JPanel();
        JButton addButton = new JButton("Position hinzufügen");
        JButton removeButton = new JButton("Position löschen");

        // Add button functionality
        addButton.addActionListener(e -> {
            positionenTableModel.addRow(new Object[] { "", "", 0.00, "HUR", 0.00, 0.00, "S" }); // Standardwerte
        });

        removeButton.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow != -1) {
                positionenTableModel.removeRow(selectedRow);
            } else {
                JOptionPane.showMessageDialog(positionenTab, "Keine Position ausgewählt.", "Fehler",
                        JOptionPane.WARNING_MESSAGE);
            }
        });

        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);

        // Add button panel to the bottom of the main panel
        positionenTab.add(buttonPanel, BorderLayout.SOUTH);

        return positionenTab;
    }

    public static List<Map<String, Object>> getAllPositions() {
        List<Map<String, Object>> positions = new ArrayList<>();
        for (int i = 0; i < positionenTableModel.getRowCount(); i++) {
            Map<String, Object> position = new LinkedHashMap<>();
            for (int j = 0; j < positionenTableModel.getColumnCount(); j++) {
                position.put(positionenTableModel.getColumnName(j), positionenTableModel.getValueAt(i, j));
            }
            positions.add(position);
        }
        return positions;
    }

    public static void setAllPositions(List<Map<String, Object>> positions) {
        positionenTableModel.setRowCount(0); // Clear existing rows
        for (Map<String, Object> position : positions) {
            Object[] row = new Object[positionenTableModel.getColumnCount()];
            for (int j = 0; j < positionenTableModel.getColumnCount(); j++) {
                row[j] = position.get(positionenTableModel.getColumnName(j));
            }
            positionenTableModel.addRow(row);
        }
    }

    private static JPanel createSummenUndSteuernTab() {
        // Create German number format
        NumberFormat germanFormat = NumberFormat.getNumberInstance(Locale.GERMANY);
        germanFormat.setMinimumFractionDigits(2);
        germanFormat.setMaximumFractionDigits(2);

        // Create custom text field with decimal formatting
        class DecimalTextField extends JTextField {
            private final NumberFormat format;

            public DecimalTextField(NumberFormat format) {
                this.format = format;
                setHorizontalAlignment(JTextField.RIGHT);

                // Add focus listener for formatting
                addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e) {
                        formatInput();
                    }
                });
            }

            private void formatInput() {
                try {
                    String value = getText().trim();
                    if (!value.isEmpty()) {
                        Number number = format.parse(value);
                        setText(format.format(number));
                    }
                } catch (ParseException e) {
                    // Restore last valid value or clear if none exists
                    setText("");
                }
            }
        }

        // Initialize fields with decimal formatting
        summenUndSteuernFieldsMap.put("Gesamtsumme netto", new DecimalTextField(germanFormat));
        summenUndSteuernFieldsMap.put("Steuer 19%", new DecimalTextField(germanFormat));
        summenUndSteuernFieldsMap.put("Steuer 7%", new DecimalTextField(germanFormat));
        summenUndSteuernFieldsMap.put("Gesamtsumme brutto", new DecimalTextField(germanFormat));

        // Set preferred size for all fields
        for (JTextField field : summenUndSteuernFieldsMap.values()) {
            field.setPreferredSize(new Dimension(200, field.getPreferredSize().height));
        }

        // Create the main panel with GridBagLayout
        JPanel summenUndSteuernTab = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        int row = 0;
        for (Map.Entry<String, JTextField> entry : summenUndSteuernFieldsMap.entrySet()) {
            // Add Label
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            JLabel label = new JLabel(entry.getKey() + ":");
            label.setHorizontalAlignment(SwingConstants.RIGHT);
            summenUndSteuernTab.add(label, gbc);

            // Add TextField
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            summenUndSteuernTab.add(entry.getValue(), gbc);

            row++;
        }

        // Add a vertical spacer to push content to the top
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0; // Fills the remaining vertical space
        summenUndSteuernTab.add(Box.createVerticalGlue(), gbc);

        summenUndSteuernTab.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        return summenUndSteuernTab;
    }

    // Update the setter method to handle number formatting
    public static void setSummenUndSteuernField(String fieldName, String value) {
        JTextField field = summenUndSteuernFieldsMap.get(fieldName);
        if (field != null) {
            try {
                // Parse the value and format it according to German locale
                if (value != null && !value.trim().isEmpty()) {
                    // Handle both dot and comma as decimal separator
                    String normalizedValue = value.replace(',', '.');
                    double number = Double.parseDouble(normalizedValue);
                    NumberFormat germanFormat = NumberFormat.getNumberInstance(Locale.GERMANY);
                    germanFormat.setMinimumFractionDigits(2);
                    germanFormat.setMaximumFractionDigits(2);
                    field.setText(germanFormat.format(number));
                } else {
                    field.setText("");
                }
            } catch (NumberFormatException e) {
                field.setText("");
            }
        }
    }

    public static String getSummenUndSteuernField(String fieldName) {
        JTextField field = summenUndSteuernFieldsMap.get(fieldName);
        return field != null ? field.getText() : null;
    }

    private static JPanel createEinstellungenTab() {
        // Initialize the fields
        einstellungenFieldsMap.put("API Key", new JPasswordField(30));
        einstellungenFieldsMap.put("Basis URL", new JTextField(30));
        einstellungenFieldsMap.put("Modell", new JTextField(30));
        einstellungenFieldsMap.put("Steuernummer", new JTextField(30));
        einstellungenFieldsMap.put("USt-ID", new JTextField(30));
        JTextArea adresseField = new JTextArea(5, 30);
        adresseField.setLineWrap(true);
        adresseField.setWrapStyleWord(true);
        einstellungenFieldsMap.put("Adresse", new JScrollPane(adresseField));

        // Load settings on tab creation
        loadEinstellungen();

        // Create main panel with GridBagLayout
        JPanel einstellungenTab = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        int row = 0;
        for (Map.Entry<String, JComponent> entry : einstellungenFieldsMap.entrySet()) {
            // Add Label
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            einstellungenTab.add(new JLabel(entry.getKey() + ":"), gbc);

            // Add Field
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            einstellungenTab.add(entry.getValue(), gbc);

            row++;
        }

        // Add Save Button
        JButton saveButton = new JButton("Speichern");
        saveButton.addActionListener(e -> saveEinstellungen());

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weighty = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        einstellungenTab.add(saveButton, gbc);

        // Add a vertical spacer to push fields to the top
        gbc.gridy = row + 1;
        gbc.weighty = 1.0; // Fills the remaining vertical space
        einstellungenTab.add(Box.createVerticalGlue(), gbc);

        einstellungenTab.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        return einstellungenTab;
    }

    private static void saveEinstellungen() {
        prefs.put("ApiKey", ((JTextField) einstellungenFieldsMap.get("API Key")).getText());
        prefs.put("BaseUrl", ((JTextField) einstellungenFieldsMap.get("Basis URL")).getText());
        prefs.put("ModelRepo", ((JTextField) einstellungenFieldsMap.get("Modell")).getText());
        prefs.put("Steuernummer", ((JTextField) einstellungenFieldsMap.get("Steuernummer")).getText());
        prefs.put("UStID", ((JTextField) einstellungenFieldsMap.get("USt-ID")).getText());
        prefs.put("Adresse",
                ((JTextArea) ((JScrollPane) einstellungenFieldsMap.get("Adresse")).getViewport().getView()).getText());
        JOptionPane.showMessageDialog(null, "Einstellungen gespeichert.", "Speichern", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void loadEinstellungen() {
        ((JTextField) einstellungenFieldsMap.get("API Key")).setText(prefs.get("ApiKey", prefs.get("OpenAIKey", "")));
        ((JTextField) einstellungenFieldsMap.get("Basis URL")).setText(prefs.get("BaseUrl", ""));
        ((JTextField) einstellungenFieldsMap.get("Modell")).setText(prefs.get("ModelRepo", ""));
        ((JTextField) einstellungenFieldsMap.get("Steuernummer")).setText(prefs.get("Steuernummer", ""));
        ((JTextField) einstellungenFieldsMap.get("USt-ID")).setText(prefs.get("UStID", ""));
        ((JTextArea) ((JScrollPane) einstellungenFieldsMap.get("Adresse")).getViewport().getView())
                .setText(prefs.get("Adresse", ""));
    }

    private static void configureDragAndDrop(JPanel panel, JLabel messageLabel, JFrame frame, JLabel statusBar,
            JSplitPane splitPane) {
        new DropTarget(panel, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                panel.setBackground(Color.LIGHT_GRAY);
            }

            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                // No action needed
            }

            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                // No action needed
            }

            @Override
            public void dragExit(DropTargetEvent dte) {
                panel.setBackground(Color.WHITE);
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                panel.setBackground(Color.WHITE);
                dtde.acceptDrop(DnDConstants.ACTION_COPY);
                handleFileDrop(dtde, messageLabel, frame, statusBar, splitPane);
            }
        });
    }

    private static String generateJsonFilePath(String pdfFilePath, String suffix) {
        File pdfFile = new File(pdfFilePath).getAbsoluteFile(); // Ensure absolute path
        String parentPath = pdfFile.getParent(); // Get the directory of the file
        String baseName = pdfFile.getName().replaceAll("\\.pdf$", ""); // Get the filename without extension
        return new File(parentPath, "_" + baseName + "." + suffix + ".json").getAbsolutePath();
    }

    private static void runOCRScript(String scriptPath, String pdfFilePath, String jsonFilePath)
            throws IOException, InterruptedException {
        // Build the command
        List<String> command = new ArrayList<>();

        // Use the python from the local venv
        String currentPath = System.getProperty("user.dir");
        String pythonExecutable = currentPath + File.separator + "venv" + File.separator + "bin" + File.separator
                + "python";

        // Fallback or check if exists? For now assume venv structure is standard.
        // If venv doesn't exist, one might want to fallback to "python3" or "python",
        // but strictly the dependencies are in venv.

        command.add(pythonExecutable);
        command.add(scriptPath);
        command.add(pdfFilePath);
        command.add(jsonFilePath);

        // Process builder
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        // Redirect error stream to standard output
        processBuilder.redirectErrorStream(true);

        // Start the process
        Process process = processBuilder.start();

        // Read the output
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        // Wait for the process to finish
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            System.out.println("OCR completed successfully. JSON file created at: " + jsonFilePath);
        } else {
            System.err.println("OCR script failed with exit code: " + exitCode);
        }
    }

    private static String ocr(File file) {
        // Get the current working directory
        String currentPath = System.getProperty("user.dir");

        // Path to the ocr.py script
        String ocrScriptPath = currentPath + File.separator + "ocr.py";

        // Generate JSON file path
        String jsonFilePath = generateJsonFilePath(file.getAbsolutePath(), "ocr");
        String pdfFilePath = file.getAbsolutePath();

        // Print the path
        System.out.println("Current running path: " + currentPath);
        System.out.println("OCR program: " + ocrScriptPath);
        System.out.println("PDF file path: " + pdfFilePath);
        System.out.println("JSON file path: " + jsonFilePath);

        System.out.println("JSON file path: " + jsonFilePath);

        String mdFilePath = jsonFilePath.replace(".json", ".md");
        File ocrMdFile = new File(mdFilePath);

        System.out.println("DEBUG: Checking for cache at: " + mdFilePath);
        System.out.println("DEBUG: File exists: " + ocrMdFile.exists());
        System.out.println("DEBUG: File length: " + ocrMdFile.length());
        System.out.println("DEBUG: File absolute path: " + ocrMdFile.getAbsolutePath());

        if (ocrMdFile.exists() && ocrMdFile.length() > 0) {
            System.out.println("OCR cache found (Markdown). Skipping OCR execution.");
            return jsonFilePath;
        }

        try {
            runOCRScript(ocrScriptPath, pdfFilePath, jsonFilePath);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return jsonFilePath;
    }

    private static InvoiceResponse.Invoice analyzeWithAI(String jsonFilePath, String pdfFilePath) {
        String ocrJsonResult = FileUtils.readFileAsText(jsonFilePath);
        String ocrMdResult = "";
        try {
            ocrMdResult = FileUtils.readFileAsText(jsonFilePath.replace(".json", ".md"));
        } catch (Exception e) {
            System.err.println("Markdown file not found or could not be read: " + e.getMessage());
        }

        if (ocrJsonResult == null) {
            System.err.println("OCR JSON result is null. Aborting AI analysis.");
            return null;
        }

        String sellerTaxNo = prefs.get("Steuernummer", prefs.get("Ust-ID", "-"));
        String sellerCompanyAddress = prefs.get("Adresse", "-");
        String postProcessingPrompt = EXTRACTION_PROMPT
                .replace("${SELLER_ADDRESS}", sellerCompanyAddress.trim())
                .replace("${SELLER_TAX_NO}", sellerTaxNo)
                .replace("${PPOCR_RESULT}", ocrJsonResult.trim())
                .replace("${MD_RESULT}", ocrMdResult != null ? ocrMdResult.trim() : "");

        System.out.println("Prompt: " + postProcessingPrompt);

        return processWithOpenAI(postProcessingPrompt, pdfFilePath);
    }

    private static InvoiceResponse getInvoiceResponseFromCache(String pdfFilePath) {
        String aiAnalysisFile = generateJsonFilePath(pdfFilePath, "ai");
        File cacheFile = new File(aiAnalysisFile);
        if (cacheFile.exists()) {
            String cachedContent = FileUtils.readFileAsText(aiAnalysisFile);
            return JsonParser.parseInvoiceResponse(cachedContent);
        }
        return null;
    }

    private static InvoiceResponse.Invoice processWithOpenAI(String prompt, String pdfFilePath) {
        try {
            String aiAnalysisFile = generateJsonFilePath(pdfFilePath, "ai");
            var invoiceResponse = getInvoiceResponseFromCache(pdfFilePath);

            if (invoiceResponse != null) {
                return invoiceResponse.invoice;
            }

            // Build the OpenAI ChatCompletion request
            String modelRepo = prefs.get("ModelRepo", "Qwen/Qwen3-Omni-30B-A3B-Instruct");
            String apiKey = prefs.get("ApiKey", prefs.get("OpenAIKey", "no-key"));

            // Initialize OpenAI client
            String baseUrl = prefs.get("BaseUrl", System.getenv("MLLM_OAI_ENDPOINT"));
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = "http://localhost:8901"; // Default
            }

            // Auto-append /v1 if missing and likely needed (standard OpenAI behavior)
            // But we respect exact user input if they know what they are doing, unless it's
            // just the root domain.
            if (!baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
                baseUrl = baseUrl.replaceAll("/$", "") + "/v1";
            }

            String chatEndpoint = baseUrl + "/chat/completions";

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(600, TimeUnit.SECONDS)
                    .writeTimeout(600, TimeUnit.SECONDS)
                    .readTimeout(600, TimeUnit.SECONDS)
                    .build();

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // Ignore "prompt_logprobs" etc.

            ObjectNode rootNode = mapper.createObjectNode();
            rootNode.put("model", modelRepo);
            rootNode.put("temperature", 0.001);

            ArrayNode messagesNode = rootNode.putArray("messages");
            ObjectNode systemMessage = messagesNode.addObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", prompt);

            String jsonBody = mapper.writeValueAsString(rootNode);

            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(chatEndpoint)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("Unexpected code " + response);
                    System.err.println("Response body: " + response.body().string());
                    return null;
                }

                String responseBody = response.body().string();
                JsonNode responseNode = mapper.readTree(responseBody);

                if (responseNode.has("choices") && responseNode.get("choices").isArray()
                        && responseNode.get("choices").size() > 0) {
                    JsonNode choice = responseNode.get("choices").get(0);
                    String responseContent = choice.get("message").get("content").asText();

                    responseContent = responseContent.replace("```json", "");
                    responseContent = responseContent.replace("```", "");

                    System.out.println("Generated JSON Response:");
                    System.out.println(responseContent);

                    // Write the response content to the cache file
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(aiAnalysisFile))) {
                        writer.write(responseContent);
                        System.out.println("AI analysis written to: " + aiAnalysisFile);
                    } catch (IOException e) {
                        System.err.println("Error occurred while writing AI analysis to file.");
                        e.printStackTrace();
                    }

                    // Parse and return the response
                    invoiceResponse = JsonParser.parseInvoiceResponse(responseContent);
                    if (invoiceResponse != null) {
                        return invoiceResponse.invoice;
                    } else {
                        System.err.println("Error occurred parsing the returned AI analysis response.");
                    }
                } else {
                    System.err.println("Error: No choices in AI analysis returned.");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to process with OpenAI.");
        }
        return null;
    }

    private static void populateFormWithInvoiceData(InvoiceResponse.Invoice invoice) {
        if (invoice == null)
            return;

        // Fill sender fields
        Map<String, String> senderData = new HashMap<>();
        if (invoice.Seller != null) {
            senderData.put("Name", Optional.ofNullable(invoice.Seller.Name).orElse(""));
            senderData.put("Adresse", Optional.ofNullable(invoice.Seller.StreetName).orElse(""));
            senderData.put("PLZ", Optional.ofNullable(invoice.Seller.PostalCode).orElse(""));
            senderData.put("Ort", Optional.ofNullable(invoice.Seller.City).orElse(""));
            senderData.put("Land", Optional.ofNullable(invoice.Seller.CountryCode).orElse(""));
            senderData.put("Steuernummer/Ust-ID", Optional.ofNullable(invoice.Seller.TaxIdentificationNumber)
                    .orElse(Optional.ofNullable(invoice.Seller.TaxVATNumber).orElse("")));
        }
        setSenderData(senderData);

        // Fill recipient fields
        Map<String, String> recipientData = new HashMap<>();
        if (invoice.Buyer != null) {
            recipientData.put("Name", Optional.ofNullable(invoice.Buyer.Name).orElse(""));
            recipientData.put("Adresse", Optional.ofNullable(invoice.Buyer.StreetName).orElse(""));
            recipientData.put("PLZ", Optional.ofNullable(invoice.Buyer.PostalCode).orElse(""));
            recipientData.put("Ort", Optional.ofNullable(invoice.Buyer.City).orElse(""));
            recipientData.put("Land", Optional.ofNullable(invoice.Buyer.CountryCode).orElse(""));
            senderData.put("Steuernummer/Ust-ID", Optional.ofNullable(invoice.Buyer.TaxIdentificationNumber)
                    .orElse(Optional.ofNullable(invoice.Buyer.TaxVATNumber).orElse("")));
        }
        setRecipientData(recipientData);

        // Fill invoice details with proper date handling
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH);

        // Fill invoice details with proper date handling
        try {
            if (invoice.InvoiceDate != null) {
                LocalDate invoiceDate = LocalDate.parse(invoice.InvoiceDate);
                ((JXDatePicker) invoiceDetailsMap.get("Rechnungsdatum"))
                        .setDate(Date.from(invoiceDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
            } else {
                ((JXDatePicker) invoiceDetailsMap.get("Rechnungsdatum")).setDate(null);
            }
        } catch (DateTimeParseException e) {
            System.err.println("Failed to parse InvoiceDate: " + invoice.InvoiceDate);
            ((JXDatePicker) invoiceDetailsMap.get("Rechnungsdatum")).setDate(null);
        }

        try {
            if (invoice.DueDate != null) {
                LocalDate dueDate = LocalDate.parse(invoice.DueDate);
                ((JXDatePicker) invoiceDetailsMap.get("Zahlungsziel"))
                        .setDate(Date.from(dueDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
            } else {
                ((JXDatePicker) invoiceDetailsMap.get("Zahlungsziel")).setDate(null);
            }
        } catch (DateTimeParseException e) {
            System.err.println("Failed to parse DueDate: " + invoice.DueDate);
            ((JXDatePicker) invoiceDetailsMap.get("Zahlungsziel")).setDate(null);
        }

        ((JTextField) invoiceDetailsMap.get("Rechnungsnummer"))
                .setText(Optional.ofNullable(invoice.InvoiceNumber).orElse(""));

        if (invoice.PaymentMeans != null && invoice.PaymentMeans.PaymentInformation != null) {
            ((JTextField) invoiceDetailsMap.get("IBAN"))
                    .setText(Optional.ofNullable(invoice.PaymentMeans.PaymentInformation.IBAN).orElse(""));
            ((JTextField) invoiceDetailsMap.get("BIC"))
                    .setText(Optional.ofNullable(invoice.PaymentMeans.PaymentInformation.BIC).orElse(""));
            ((JTextField) invoiceDetailsMap.get("Bank Name"))
                    .setText(Optional.ofNullable(invoice.PaymentMeans.PaymentInformation.BankName).orElse(""));
            ((JTextField) invoiceDetailsMap.get("Zahlungsreferenz"))
                    .setText(Optional.ofNullable(invoice.PaymentMeans.PaymentInformation.PaymentReference).orElse(""));
            ((JTextField) invoiceDetailsMap.get("Zahlungsempfänger"))
                    .setText(Optional.ofNullable(invoice.PaymentMeans.PaymentInformation.PaymentReceiver).orElse(""));
        } else {
            ((JTextField) invoiceDetailsMap.get("IBAN")).setText("");
            ((JTextField) invoiceDetailsMap.get("BIC")).setText("");
            ((JTextField) invoiceDetailsMap.get("Bank Name")).setText("");
            ((JTextField) invoiceDetailsMap.get("Zahlungsreferenz")).setText("");
            ((JTextField) invoiceDetailsMap.get("Zahlungsempfänger")).setText("");
        }

        // Fill summen und steuern fields
        if (invoice.MonetarySummation != null) {
            setSummenUndSteuernField("Gesamtsumme netto",
                    Optional.ofNullable(invoice.MonetarySummation.TaxExclusiveAmount)
                            .map(String::valueOf)
                            .orElse("0,00"));
            setSummenUndSteuernField("Gesamtsumme brutto",
                    Optional.ofNullable(invoice.MonetarySummation.PayableAmount)
                            .map(String::valueOf)
                            .orElse("0,00"));
        } else {
            setSummenUndSteuernField("Gesamtsumme netto", "0,00");
            setSummenUndSteuernField("Gesamtsumme brutto", "0,00");
        }

        if (invoice.Tax != null) {
            String taxAmount = Optional.ofNullable(invoice.Tax.TaxAmount)
                    .map(String::valueOf)
                    .orElse("0,00");
            String taxCode = Optional.ofNullable(invoice.Tax.TaxCategoryCode).orElse("");

            setSummenUndSteuernField("Steuer 19%",
                    taxCode.equals("S") ? taxAmount : "0,00");
            setSummenUndSteuernField("Steuer 7%",
                    taxCode.equals("AA") ? taxAmount : "0,00");
        } else {
            setSummenUndSteuernField("Steuer 19%", "0,00");
            setSummenUndSteuernField("Steuer 7%", "0,00");
        }

        // Fill positions
        List<Map<String, Object>> positions = new ArrayList<>();
        if (invoice.InvoiceLines != null) {
            for (InvoiceResponse.Invoice.InvoiceLine line : invoice.InvoiceLines) {
                if (line != null) {
                    Map<String, Object> position = new LinkedHashMap<>();
                    position.put("Pos. Nr.", Optional.ofNullable(line.LineID).orElse(""));
                    position.put("Beschreibung", Optional.ofNullable(line.ProductName).orElse(""));
                    position.put("Menge", Optional.ofNullable(line.Quantity).orElse(0.0));
                    position.put("Einheit", Optional.ofNullable(line.Unit).orElse(""));
                    position.put("Einzelpreis", Optional.ofNullable(line.UnitPrice).orElse(0.0));
                    position.put("Gesamtpreis", Optional.ofNullable(line.LineTotalAmount).orElse(0.0));
                    position.put("Steuerklasse", Optional.ofNullable(line.TaxCategoryCode).orElse(""));
                    positions.add(position);
                }
            }
        }
        setAllPositions(positions);
    }

    private static InvoiceResponse.Invoice collectFormData() {
        // Create German number format for parsing
        NumberFormat germanFormat = NumberFormat.getNumberInstance(Locale.GERMANY);
        germanFormat.setMinimumFractionDigits(2);
        germanFormat.setMaximumFractionDigits(2);

        // Helper method to parse German formatted numbers
        Function<String, Double> parseGermanDouble = (String value) -> {
            try {
                if (value == null || value.trim().isEmpty()) {
                    return 0.0;
                }
                return germanFormat.parse(value).doubleValue();
            } catch (ParseException e) {
                System.err.println("Failed to parse number: " + value);
                return 0.0;
            }
        };

        InvoiceResponse.Invoice invoice = new InvoiceResponse.Invoice();

        // Collect sender data
        InvoiceResponse.Invoice.Party seller = new InvoiceResponse.Invoice.Party();
        seller.Name = senderFieldsMap.get("Name").getText();
        seller.StreetName = senderFieldsMap.get("Adresse").getText();
        seller.PostalCode = senderFieldsMap.get("PLZ").getText();
        seller.City = senderFieldsMap.get("Ort").getText();
        seller.CountryCode = senderFieldsMap.get("Land").getText();
        String taxInfo = senderFieldsMap.get("Steuernummer/Ust-ID").getText();

        if (taxInfo != null && !taxInfo.isEmpty()) {
            if (taxInfo.contains("/")) {
                seller.TaxIdentificationNumber = taxInfo;
            } else {
                // Strip country code if present in VAT ID (e.g. DE123 -> 123)
                // because ZUGFeRD writer prepends the country code automatically.
                String countryCode = seller.CountryCode != null ? seller.CountryCode.trim() : "";
                if (!countryCode.isEmpty() && taxInfo.toUpperCase().startsWith(countryCode.toUpperCase())) {
                    seller.TaxVATNumber = taxInfo.substring(countryCode.length()).trim();
                } else {
                    seller.TaxVATNumber = taxInfo;
                }
            }
        }
        invoice.Seller = seller;

        // Collect recipient data
        InvoiceResponse.Invoice.Party buyer = new InvoiceResponse.Invoice.Party();
        buyer.Name = recipientFieldsMap.get("Name").getText();
        buyer.StreetName = recipientFieldsMap.get("Adresse").getText();
        buyer.PostalCode = recipientFieldsMap.get("PLZ").getText();
        buyer.City = recipientFieldsMap.get("Ort").getText();
        buyer.CountryCode = recipientFieldsMap.get("Land").getText();
        String taxInfoRecipient = recipientFieldsMap.get("Steuernummer/Ust-ID").getText();

        if (taxInfoRecipient != null && !taxInfoRecipient.isEmpty()) {
            if (taxInfoRecipient.contains("/")) {
                buyer.TaxIdentificationNumber = taxInfoRecipient;
            } else {
                // Strip country code if present in VAT ID
                String countryCode = buyer.CountryCode != null ? buyer.CountryCode.trim() : "";
                if (!countryCode.isEmpty() && taxInfoRecipient.toUpperCase().startsWith(countryCode.toUpperCase())) {
                    buyer.TaxVATNumber = taxInfoRecipient.substring(countryCode.length()).trim();
                } else {
                    buyer.TaxVATNumber = taxInfoRecipient;
                }
            }
        }
        invoice.Buyer = buyer;

        // Collect invoice details with proper date formatting for ZUGFeRD
        try {
            Date rechnungsdatum = ((JXDatePicker) invoiceDetailsMap.get("Rechnungsdatum")).getDate();
            Date zahlungsziel = ((JXDatePicker) invoiceDetailsMap.get("Zahlungsziel")).getDate();

            if (rechnungsdatum == null || zahlungsziel == null) {
                throw new IllegalStateException("Rechnungsdatum und Zahlungsziel müssen ausgefüllt sein.");
            }

            // Convert to LocalDate first
            LocalDate invoiceLocalDate = rechnungsdatum.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            LocalDate dueLocalDate = zahlungsziel.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();

            // Format as ISO date string (YYYY-MM-DD)
            invoice.InvoiceDate = invoiceLocalDate.toString(); // This will format as YYYY-MM-DD
            invoice.DueDate = dueLocalDate.toString(); // This will format as YYYY-MM-DD

        } catch (Exception e) {
            throw new IllegalStateException("Fehler beim Verarbeiten der Datumsangaben: " + e.getMessage());
        }

        invoice.InvoiceNumber = ((JTextField) invoiceDetailsMap.get("Rechnungsnummer")).getText();
        invoice.DocumentCurrencyCode = ((JTextField) invoiceDetailsMap.get("Währung")).getText();

        invoice.PaymentMeans = new InvoiceResponse.Invoice.PaymentMeans();
        invoice.PaymentMeans.PaymentInformation = new InvoiceResponse.Invoice.PaymentMeans.PaymentInformation();
        invoice.PaymentMeans.PaymentInformation.PaymentReceiver = ((JTextField) invoiceDetailsMap
                .get("Zahlungsempfänger")).getText();
        invoice.PaymentMeans.PaymentInformation.BankName = ((JTextField) invoiceDetailsMap.get("Bank Name")).getText();
        invoice.PaymentMeans.PaymentInformation.IBAN = ((JTextField) invoiceDetailsMap.get("IBAN")).getText();
        invoice.PaymentMeans.PaymentInformation.BIC = ((JTextField) invoiceDetailsMap.get("BIC")).getText();
        invoice.PaymentMeans.PaymentInformation.PaymentReference = ((JTextField) invoiceDetailsMap
                .get("Zahlungsreferenz")).getText();

        // Collect monetary summation data
        InvoiceResponse.Invoice.MonetarySummation monetarySummation = new InvoiceResponse.Invoice.MonetarySummation();
        monetarySummation.TaxExclusiveAmount = parseGermanDouble.apply(getSummenUndSteuernField("Gesamtsumme netto"));
        monetarySummation.TaxInclusiveAmount = parseGermanDouble.apply(getSummenUndSteuernField("Gesamtsumme brutto"));
        monetarySummation.PayableAmount = parseGermanDouble.apply(getSummenUndSteuernField("Gesamtsumme brutto"));
        invoice.MonetarySummation = monetarySummation;

        // Collect tax data
        InvoiceResponse.Invoice.Tax tax = new InvoiceResponse.Invoice.Tax();
        double steuer19 = parseGermanDouble.apply(getSummenUndSteuernField("Steuer 19%"));
        double steuer7 = parseGermanDouble.apply(getSummenUndSteuernField("Steuer 7%"));
        tax.TaxCategoryCode = steuer19 > 0 ? "S" : "AA";
        tax.TaxPercentage = tax.TaxCategoryCode.equals("S") ? 19.0 : 7.0;
        tax.TaxAmount = steuer19 + steuer7;
        invoice.Tax = tax;

        // Collect position data
        List<Map<String, Object>> positions = getAllPositions();
        List<InvoiceResponse.Invoice.InvoiceLine> invoiceLines = new ArrayList<>();
        for (Map<String, Object> position : positions) {
            InvoiceResponse.Invoice.InvoiceLine line = new InvoiceResponse.Invoice.InvoiceLine();
            line.LineID = String.valueOf(position.get("Pos. Nr."));
            line.ProductName = String.valueOf(position.get("Beschreibung"));

            // Parse numbers using German format
            Object mengeObj = position.get("Menge");
            Object einzelpreisObj = position.get("Einzelpreis");
            Object gesamtpreisObj = position.get("Gesamtpreis");

            line.Quantity = mengeObj instanceof Number ? ((Number) mengeObj).doubleValue()
                    : parseGermanDouble.apply(String.valueOf(mengeObj));

            line.UnitPrice = einzelpreisObj instanceof Number ? ((Number) einzelpreisObj).doubleValue()
                    : parseGermanDouble.apply(String.valueOf(einzelpreisObj));

            line.LineTotalAmount = gesamtpreisObj instanceof Number ? ((Number) gesamtpreisObj).doubleValue()
                    : parseGermanDouble.apply(String.valueOf(gesamtpreisObj));

            line.TaxCategoryCode = String.valueOf(position.get("Steuerklasse"));
            line.Unit = String.valueOf(position.get("Einheit"));
            line.TaxPercentage = line.TaxCategoryCode.equals("S") ? 19.0 : 7.0;
            invoiceLines.add(line);
        }
        invoice.InvoiceLines = invoiceLines;

        return invoice;
    }

    private static void processPDF(File file, JLabel statusBar, JFrame frame, JSplitPane splitPane) {
        // Create a processing panel to temporarily replace the right pane
        JPanel processingPanel = new JPanel(new BorderLayout());
        processingPanel.setBackground(new Color(0, 0, 0, 128)); // Semi-transparent black
        JLabel processingLabel = new JLabel("Verarbeitung gestartet...", SwingConstants.CENTER);
        processingLabel.setForeground(Color.WHITE);
        processingLabel.setFont(new Font("Arial", Font.BOLD, 24));
        processingPanel.add(processingLabel, BorderLayout.CENTER);

        // Save the original right component
        Component originalRightComponent = splitPane.getRightComponent();

        SwingUtilities.invokeLater(() -> {
            splitPane.setRightComponent(processingPanel); // Temporarily set the processing panel
            splitPane.setDividerLocation(640);
            splitPane.revalidate();
            splitPane.repaint();
        });

        // SwingWorker to handle the entire process
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                // Step 1: Display PDF
                publish("Lese PDF...");
                processingLabel.setText("Lese PDF...");
                displayPDF(file, splitPane);

                // Step 2: Run OCR
                publish("KI-Texterkennung (OCR)...");
                processingLabel.setText("KI-Texterkennung (OCR)...");
                String ocrJsonResultFilePath = ocr(file);

                String pdfFilePath = file.getAbsolutePath();

                // Step 3: Analyze with AI
                publish("KI-Analyse der OCR-Ergebnisse...");
                processingLabel.setText("KI-Analyse der OCR-Ergebnisse...");
                InvoiceResponse.Invoice invoice = analyzeWithAI(ocrJsonResultFilePath, pdfFilePath);

                if (invoice != null) {
                    populateFormWithInvoiceData(invoice);
                    processingLabel.setText("Rechnungsdaten vollständig korrekt erkannt.");
                    publish("Rechnungsdaten vollständig korrekt erkannt.");
                } else {
                    processingLabel.setText("Rechnungsdaten teilweise korrekt erkannt.");
                    publish("Rechnungsdaten teilweise korrekt erkannt.");
                }

                // Step 4: Convert original file to PDF/A
                publish("Konvertiere zu PDF/A-Format...");
                processingLabel.setText("Konvertiere zu PDF/A-Format...");
                String archivePdfFilePath = PdfAConverter.convertToPdfA(pdfFilePath);

                // Step 5: Validate PDF/A file
                publish("Validiere PDF/A-Datei...");
                processingLabel.setText("Validiere PDF/A-Datei...");
                PdfAConverter.validatePDFA(archivePdfFilePath);

                // update to use archive as current pdfFilePath
                Main.pdfFilePath = archivePdfFilePath;
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                // Update the status bar with the latest status
                String latestStatus = chunks.get(chunks.size() - 1);
                statusBar.setText(latestStatus != null && !latestStatus.isEmpty() ? latestStatus : "Bereit");
            }

            @Override
            protected void done() {
                try {
                    get(); // Retrieve result to check for exceptions
                    statusBar.setText("Bereit");
                } catch (Exception e) {
                    statusBar.setText("Fehler: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    // Restore the original right component
                    SwingUtilities.invokeLater(() -> {
                        splitPane.setRightComponent(originalRightComponent);
                        splitPane.setDividerLocation(640);
                        splitPane.revalidate();
                        splitPane.repaint();
                    });
                }
            }
        };

        // Execute the worker
        worker.execute();
    }

    private static void handleFileDrop(DropTargetDropEvent dtde, JLabel messageLabel, JFrame frame, JLabel statusBar,
            JSplitPane splitPane) {
        try {
            List<File> droppedFiles = (List<File>) dtde.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
            for (File file : droppedFiles) {
                if (file.getName().toLowerCase().endsWith(".pdf")) {
                    messageLabel.setText(FILE_ACCEPTED_MSG + file.getName());
                    processPDF(file, statusBar, frame, splitPane);

                } else {
                    messageLabel.setText(INVALID_FILE_MSG);
                }
            }
        } catch (Exception e) {
            messageLabel.setText(ERROR_MSG);
            e.printStackTrace();
        }
    }

    public static void displayPDF(File pdfFile, JSplitPane splitPane) {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            if (pageCount > 0) {
                ImageIcon renderedPage = new ImageIcon(pdfRenderer.renderImageWithDPI(0, 75));
                JLabel pdfView = new JLabel(renderedPage);
                JScrollPane scrollPane = new JScrollPane(pdfView);
                scrollPane.getVerticalScrollBar().setUnitIncrement(20);
                scrollPane.getHorizontalScrollBar().setUnitIncrement(20);

                // Ensure only the left pane is updated
                SwingUtilities.invokeLater(() -> {
                    splitPane.setLeftComponent(scrollPane); // Replace the left component
                    splitPane.setDividerLocation(512);
                    splitPane.revalidate(); // Recalculate the layout
                    splitPane.repaint(); // Redraw the UI
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(splitPane, "Fehler beim Laden der PDF-Datei: " + e.getMessage(), "Fehler",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
