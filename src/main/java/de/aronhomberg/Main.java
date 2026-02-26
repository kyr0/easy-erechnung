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
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.NumberFormatter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Preferences prefs = Preferences.userNodeForPackage(Main.class);

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
        splitPane.setDividerLocation(640); // Initial divider position — show settings on the right

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
        tabbedPane.setSelectedIndex(3); // Show Einstellungen tab by default

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
        einstellungenFieldsMap.put("OCR-Modell", new JTextField(30));
        einstellungenFieldsMap.put("JSON Modell", new JTextField(30));
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
        String baseUrl = ((JTextField) einstellungenFieldsMap.get("Basis URL")).getText().trim();
        String ocrModel = ((JTextField) einstellungenFieldsMap.get("OCR-Modell")).getText().trim();
        String jsonModel = ((JTextField) einstellungenFieldsMap.get("JSON Modell")).getText().trim();

        if (baseUrl.isEmpty())   baseUrl   = "http://localhost:11434";
        if (ocrModel.isEmpty())  ocrModel  = "glm-ocr:q8_0";
        if (jsonModel.isEmpty()) jsonModel = "qwen3:4b-q8_0";

        prefs.put("ApiKey",     ((JTextField) einstellungenFieldsMap.get("API Key")).getText());
        prefs.put("BaseUrl",    baseUrl);
        prefs.put("OcrModel",   ocrModel);
        prefs.put("ModelRepo",  jsonModel);
        prefs.put("Steuernummer", ((JTextField) einstellungenFieldsMap.get("Steuernummer")).getText());
        prefs.put("UStID",      ((JTextField) einstellungenFieldsMap.get("USt-ID")).getText());
        prefs.put("Adresse",
                ((JTextArea) ((JScrollPane) einstellungenFieldsMap.get("Adresse")).getViewport().getView()).getText());

        // Refresh UI to show applied defaults
        loadEinstellungen();
        JOptionPane.showMessageDialog(null, "Einstellungen gespeichert.", "Speichern", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void loadEinstellungen() {
        ((JTextField) einstellungenFieldsMap.get("API Key")).setText(prefs.get("ApiKey", prefs.get("OpenAIKey", "")));
        ((JTextField) einstellungenFieldsMap.get("Basis URL")).setText(prefs.get("BaseUrl", "http://localhost:11434"));
        ((JTextField) einstellungenFieldsMap.get("OCR-Modell")).setText(prefs.get("OcrModel", "glm-ocr:q8_0"));
        ((JTextField) einstellungenFieldsMap.get("JSON Modell")).setText(prefs.get("ModelRepo", "qwen3:4b-q8_0"));
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

    private static void configurePdfDropTarget(JComponent target, JFrame frame, JLabel statusBar,
            JSplitPane splitPane) {
        Border defaultBorder = target.getBorder();
        Border dragBorder = BorderFactory.createLineBorder(new Color(0, 120, 215), 2);

        new DropTarget(target, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                target.setBorder(dragBorder);
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
                target.setBorder(defaultBorder);
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                target.setBorder(defaultBorder);
                dtde.acceptDrop(DnDConstants.ACTION_COPY);
                handleFileDrop(dtde, null, frame, statusBar, splitPane);
            }
        });
    }

    private static String generateJsonFilePath(String pdfFilePath, String suffix) {
        File pdfFile = new File(pdfFilePath).getAbsoluteFile(); // Ensure absolute path
        String parentPath = pdfFile.getParent(); // Get the directory of the file
        String baseName = pdfFile.getName().replaceAll("\\.pdf$", ""); // Get the filename without extension
        return new File(parentPath, "_" + baseName + "." + suffix + ".json").getAbsolutePath();
    }

    /** Resolves the bun executable. Checks EASY_ERECHNUNG_BUN env var first, then PATH. */
    private static String resolveBunExecutable() {
        String override = System.getenv("EASY_ERECHNUNG_BUN");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        // bun is expected on PATH after a global install
        return "bun";
    }

    private static void throwIfCancelled(BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Verarbeitung wurde abgebrochen.");
        }
    }

    private static <T> T runWithCancellation(Callable<T> task, BooleanSupplier isCancelled) throws Exception {
        throwIfCancelled(isCancelled);

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "e-rechnung-cancellable-task");
            thread.setDaemon(true);
            return thread;
        });

        Future<T> future = executor.submit(task);
        try {
            while (true) {
                throwIfCancelled(isCancelled);
                try {
                    return future.get(250, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }
                    throw new RuntimeException(cause);
                }
            }
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    /**
     * Runs `bun run src/ocr.ts` with the given PDF as input and writes a single
     * invoice JSON object to outputJsonPath.
     * LLM settings are read from Java preferences (matching the Einstellungen tab).
     */
    private static void runBunOcrPipeline(String pdfFilePath, String outputJsonPath,
            BooleanSupplier isCancelled, AtomicReference<Process> runningProcess,
            java.util.function.Consumer<String> lineCallback)
            throws IOException, InterruptedException {
        throwIfCancelled(isCancelled);

        String currentPath = System.getProperty("user.dir");
        String ocrScriptPath = currentPath + File.separator + "src" + File.separator + "ocr.ts";
        String bun = resolveBunExecutable();

        String sellerTaxNo = prefs.get("Steuernummer", prefs.get("UStID", ""));
        String sellerAddress = prefs.get("Adresse", "");
        String baseUrl = prefs.get("BaseUrl", "");
        String apiKey = prefs.get("ApiKey", prefs.get("OpenAIKey", ""));
        String ocrModel = prefs.get("OcrModel", "");
        String jsonModel = prefs.get("ModelRepo", "");

        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = System.getenv("LLM_BASE_URL");
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "http://localhost:11434";
        }
        if (ocrModel == null || ocrModel.trim().isEmpty()) {
            ocrModel = "glm-ocr:q8_0";
        }
        if (jsonModel == null || jsonModel.trim().isEmpty()) {
            jsonModel = "qwen3:4b-q8_0";
        }
        // Strip trailing /v1 — ocr.ts normalises itself too, but be consistent
        baseUrl = baseUrl.replaceAll("/v1/?$", "").replaceAll("/$", "");

        List<String> command = new ArrayList<>(List.of(
                bun, "run", ocrScriptPath,
                "--input",          pdfFilePath,
                "--output",         outputJsonPath,
                "--seller-address", sellerAddress,
                "--seller-tax-no",  sellerTaxNo,
                "--base-url",       baseUrl,
                "--api-key",        apiKey,
                "--ocr-model",      ocrModel,
                "--json-model",     jsonModel
        ));

        System.out.println("Running bun OCR pipeline:");
        System.out.println("  " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(currentPath));
        pb.redirectErrorStream(true);  // merge stderr → stdout so we see everything

        Process process = pb.start();
        if (runningProcess != null) runningProcess.set(process);

        try {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled.getAsBoolean()) {
                        process.destroyForcibly();
                        throw new CancellationException("OCR wurde abgebrochen.");
                    }
                    System.out.println(line);
                    if (lineCallback != null) lineCallback.accept(line);
                }
            }

            throwIfCancelled(isCancelled);

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("bun ocr.ts exited with code " + exitCode +
                        " — check the output above for details");
            }
            System.out.println("OCR pipeline completed successfully. Output: " + outputJsonPath);
        } finally {
            if (runningProcess != null) runningProcess.compareAndSet(process, null);
        }
    }

    /**
     * Runs the bun/TS OCR+AI pipeline for a single PDF file.
     * Returns the path to the output JSON file containing the invoice object.
     * Uses the "ai" suffix so the output can be parsed directly by JsonParser.
     */
    private static String ocrWithBun(File file, BooleanSupplier isCancelled,
            AtomicReference<Process> runningProcess,
            java.util.function.Consumer<String> lineCallback) {
        throwIfCancelled(isCancelled);

        String aiJsonPath = generateJsonFilePath(file.getAbsolutePath(), "ai");
        File cacheFile = new File(aiJsonPath);

        if (cacheFile.exists() && cacheFile.length() > 0) {
            System.out.println("AI cache found. Skipping pipeline execution.");
            return aiJsonPath;
        }

        try {
            runBunOcrPipeline(file.getAbsolutePath(), aiJsonPath, isCancelled, runningProcess, lineCallback);
        } catch (CancellationException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.err.println("ERROR: bun OCR pipeline failed: " + e.getMessage());
        }
        return aiJsonPath;
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
            recipientData.put("Steuernummer/Ust-ID", Optional.ofNullable(invoice.Buyer.TaxIdentificationNumber)
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
            // Legacy nested format
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
            // New flat format
            ((JTextField) invoiceDetailsMap.get("IBAN")).setText(Optional.ofNullable(invoice.IBAN).orElse(""));
            ((JTextField) invoiceDetailsMap.get("BIC")).setText(Optional.ofNullable(invoice.BIC).orElse(""));
            ((JTextField) invoiceDetailsMap.get("Bank Name")).setText(Optional.ofNullable(invoice.BankName).orElse(""));
            ((JTextField) invoiceDetailsMap.get("Zahlungsreferenz")).setText(Optional.ofNullable(invoice.PaymentReference).orElse(""));
            ((JTextField) invoiceDetailsMap.get("Zahlungsempfänger")).setText(Optional.ofNullable(invoice.PaymentReceiver).orElse(""));
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
        AtomicReference<Process> runningOcrProcess = new AtomicReference<>();
        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        AtomicReference<SwingWorker<Void, String>> workerRef = new AtomicReference<>();
        BooleanSupplier isCancelled = () -> cancelRequested.get()
            || (workerRef.get() != null && workerRef.get().isCancelled())
            || Thread.currentThread().isInterrupted();

        // Delete any stale OCR preview images from a previous run
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        File ocrPreviewFile = new File(tmpDir, "ocr_preview.png");
        if (ocrPreviewFile.exists()) ocrPreviewFile.delete();
        // Clean up per-page previews from previous runs
        for (File f : tmpDir.listFiles((dir, name) -> name.startsWith("ocr_preview_") && name.endsWith(".png"))) {
            f.delete();
        }

        // ── Multi-page tabbed preview panel ─────────────────────────────────
        // Outer panel: tabs on top (one per page), status + cancel at bottom
        JPanel processingPanel = new JPanel(new BorderLayout());
        processingPanel.setBackground(Color.BLACK);

        JTabbedPane pageTabs = new JTabbedPane();
        pageTabs.setTabPlacement(JTabbedPane.TOP);
        processingPanel.add(pageTabs, BorderLayout.CENTER);

        // Track how many page tabs we've already added
        AtomicInteger knownPageCount = new AtomicInteger(0);

        // Poll for new per-page preview images written by ocr.ts
        javax.swing.Timer previewTimer = new javax.swing.Timer(500, e -> {
            // Check for ocr_preview_N.png files we haven't loaded yet
            int next = knownPageCount.get() + 1;
            File nextPreview = new File(tmpDir, "ocr_preview_" + next + ".png");
            while (nextPreview.exists() && nextPreview.length() > 0) {
                try {
                    Image img = new ImageIcon(nextPreview.getAbsolutePath()).getImage();
                    // Create a panel that paints this page's image with a dark overlay
                    final Image pageImg = img;
                    JPanel pagePanel = new JPanel(new BorderLayout()) {
                        @Override
                        protected void paintComponent(Graphics g) {
                            super.paintComponent(g);
                            int pw = getWidth();
                            int ph = getHeight();
                            int iw = pageImg.getWidth(null);
                            int ih = pageImg.getHeight(null);
                            if (iw > 0 && ih > 0) {
                                double scale = Math.min((double) pw / iw, (double) ph / ih);
                                int dw = (int) (iw * scale);
                                int dh = (int) (ih * scale);
                                int x = (pw - dw) / 2;
                                int y = (ph - dh) / 2;
                                g.drawImage(pageImg, x, y, dw, dh, null);
                                g.setColor(new Color(0, 0, 0, 160));
                                g.fillRect(0, 0, pw, ph);
                            }
                        }
                    };
                    pagePanel.setBackground(Color.BLACK);

                    JLabel pageStatus = new JLabel("Verarbeitung...", SwingConstants.CENTER);
                    pageStatus.setForeground(Color.WHITE);
                    pageStatus.setFont(new Font("Arial", Font.BOLD, 18));
                    pagePanel.add(pageStatus, BorderLayout.CENTER);

                    pageTabs.addTab("Seite " + next, pagePanel);
                    pageTabs.setSelectedIndex(pageTabs.getTabCount() - 1);
                    knownPageCount.set(next);
                } catch (Exception ignored) {}
                next = knownPageCount.get() + 1;
                nextPreview = new File(tmpDir, "ocr_preview_" + next + ".png");
            }
        });
        previewTimer.start();

        JLabel processingLabel = new JLabel("Verarbeitung gestartet...", SwingConstants.CENTER);
        processingLabel.setForeground(Color.WHITE);
        processingLabel.setFont(new Font("Arial", Font.BOLD, 24));
        // If no tabs yet, show label as a fallback placeholder
        JPanel placeholderPanel = new JPanel(new BorderLayout());
        placeholderPanel.setBackground(Color.BLACK);
        placeholderPanel.add(processingLabel, BorderLayout.CENTER);
        pageTabs.addTab("OCR", placeholderPanel);

        // ── JSON extraction tab ─────────────────────────────────────────────
        JPanel jsonPanel = new JPanel(new BorderLayout());
        jsonPanel.setBackground(Color.BLACK);
        JLabel jsonStatusLabel = new JLabel("Warte auf OCR...", SwingConstants.CENTER);
        jsonStatusLabel.setForeground(Color.WHITE);
        jsonStatusLabel.setFont(new Font("Arial", Font.BOLD, 18));
        jsonPanel.add(jsonStatusLabel, BorderLayout.CENTER);

        // Log area for JSON extraction details
        JTextArea jsonLogArea = new JTextArea();
        jsonLogArea.setEditable(false);
        jsonLogArea.setBackground(new Color(30, 30, 30));
        jsonLogArea.setForeground(new Color(180, 230, 180));
        jsonLogArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        jsonLogArea.setLineWrap(true);
        jsonLogArea.setWrapStyleWord(true);
        JScrollPane jsonLogScroll = new JScrollPane(jsonLogArea);
        jsonLogScroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        jsonPanel.add(jsonLogScroll, BorderLayout.CENTER);
        jsonPanel.add(jsonStatusLabel, BorderLayout.NORTH);
        pageTabs.addTab("JSON", jsonPanel);

        JPanel processingControlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        processingControlPanel.setOpaque(false);
        JButton cancelButton = new JButton("Abbrechen");
        processingControlPanel.add(cancelButton);
        processingPanel.add(processingControlPanel, BorderLayout.SOUTH);

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
            throwIfCancelled(isCancelled);

                // Step 1: Display PDF
                publish("Lese PDF...");
                processingLabel.setText("Lese PDF...");
                Main.pdfFilePath = file.getAbsolutePath();
                displayPDF(file, splitPane, frame, statusBar);
            throwIfCancelled(isCancelled);

                // Step 2+3: OCR + AI extraction in one bun call
                publish("OCR & KI-Analyse (bun/TS)...");
                processingLabel.setText("OCR & KI-Analyse (bun/TS)...");

            // Line callback: parse structured log lines from ocr.ts to update page tabs
            java.util.function.Consumer<String> lineCallback = outputLine -> {
                // Match "[PAGE N] ..." status lines
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^\\[PAGE (\\d+)\\] (.+)$").matcher(outputLine);
                if (m.find()) {
                    int pageIdx = Integer.parseInt(m.group(1));
                    String msg = m.group(2);
                    SwingUtilities.invokeLater(() -> {
                        for (int t = 0; t < pageTabs.getTabCount(); t++) {
                            if (pageTabs.getTitleAt(t).equals("Seite " + pageIdx)) {
                                Component c = pageTabs.getComponentAt(t);
                                if (c instanceof JPanel) {
                                    JPanel pp = (JPanel) c;
                                    Component center = ((BorderLayout) pp.getLayout())
                                            .getLayoutComponent(BorderLayout.CENTER);
                                    if (center instanceof JLabel) {
                                        ((JLabel) center).setText(msg);
                                    }
                                }
                                pageTabs.setSelectedIndex(t);
                                break;
                            }
                        }
                    });
                }

                // Match "[JSON] ..." status lines — update JSON tab
                java.util.regex.Matcher jm = java.util.regex.Pattern
                        .compile("^\\[JSON\\] (.+)$").matcher(outputLine);
                if (jm.find()) {
                    String msg = jm.group(1);
                    SwingUtilities.invokeLater(() -> {
                        jsonStatusLabel.setText(msg);
                        jsonLogArea.append(msg + "\n");
                        jsonLogArea.setCaretPosition(jsonLogArea.getDocument().getLength());
                        // Switch to JSON tab
                        for (int t = 0; t < pageTabs.getTabCount(); t++) {
                            if (pageTabs.getTitleAt(t).equals("JSON")) {
                                pageTabs.setSelectedIndex(t);
                                break;
                            }
                        }
                    });
                }

                // Update placeholder label for general progress lines
                if (outputLine.startsWith("Processing page ")) {
                    SwingUtilities.invokeLater(() -> processingLabel.setText(outputLine));
                }
            };

            String aiJsonPath = ocrWithBun(file, isCancelled, runningOcrProcess, lineCallback);
            throwIfCancelled(isCancelled);

                String cachedJson = FileUtils.readFileAsText(aiJsonPath);
                InvoiceResponse invoiceResponse = cachedJson != null
                        ? JsonParser.parseInvoiceResponse(cachedJson) : null;
                InvoiceResponse.Invoice invoice = invoiceResponse != null ? invoiceResponse.invoice : null;
            throwIfCancelled(isCancelled);

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
                String archivePdfFilePath = runWithCancellation(() -> PdfAConverter.convertToPdfA(pdfFilePath),
                        isCancelled);
                throwIfCancelled(isCancelled);

                // Step 5: Validate PDF/A file
                publish("Validiere PDF/A-Datei...");
                processingLabel.setText("Validiere PDF/A-Datei...");
                runWithCancellation(() -> {
                    PdfAConverter.validatePDFA(archivePdfFilePath);
                    return null;
                }, isCancelled);
                throwIfCancelled(isCancelled);

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
                    statusBar.setText(isCancelled() ? "Verarbeitung abgebrochen." : "Bereit");
                } catch (CancellationException e) {
                    statusBar.setText("Verarbeitung abgebrochen.");
                } catch (Exception e) {
                    statusBar.setText("Fehler: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    cancelRequested.set(true);
                    previewTimer.stop();

                    Process process = runningOcrProcess.getAndSet(null);
                    if (process != null && process.isAlive()) {
                        process.destroyForcibly();
                    }

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

        workerRef.set(worker);

        cancelButton.addActionListener(e -> {
            cancelRequested.set(true);
            cancelButton.setEnabled(false);
            processingLabel.setText("Abbrechen läuft...");
            statusBar.setText("Abbrechen läuft...");

            Process process = runningOcrProcess.get();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }

            SwingWorker<Void, String> runningWorker = workerRef.get();
            if (runningWorker != null) {
                runningWorker.cancel(true);
            }
        });

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
                    if (messageLabel != null) {
                        messageLabel.setText(FILE_ACCEPTED_MSG + file.getName());
                    } else if (statusBar != null) {
                        statusBar.setText(FILE_ACCEPTED_MSG + file.getName());
                    }
                    processPDF(file, statusBar, frame, splitPane);

                } else {
                    if (messageLabel != null) {
                        messageLabel.setText(INVALID_FILE_MSG);
                    } else if (statusBar != null) {
                        statusBar.setText(INVALID_FILE_MSG);
                    }
                }
            }
        } catch (Exception e) {
            if (messageLabel != null) {
                messageLabel.setText(ERROR_MSG);
            } else if (statusBar != null) {
                statusBar.setText(ERROR_MSG);
            }
            e.printStackTrace();
        }
    }

    public static void displayPDF(File pdfFile, JSplitPane splitPane, JFrame frame, JLabel statusBar) {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            if (pageCount <= 0) return;

            if (pageCount == 1) {
                // Single page — simple scroll pane, no tabs
                ImageIcon renderedPage = new ImageIcon(pdfRenderer.renderImageWithDPI(0, 75));
                JLabel pdfView = new JLabel(renderedPage);
                JScrollPane scrollPane = new JScrollPane(pdfView);
                scrollPane.getVerticalScrollBar().setUnitIncrement(20);
                scrollPane.getHorizontalScrollBar().setUnitIncrement(20);
                configurePdfDropTarget(scrollPane, frame, statusBar, splitPane);

                SwingUtilities.invokeLater(() -> {
                    splitPane.setLeftComponent(scrollPane);
                    splitPane.setDividerLocation(512);
                    splitPane.revalidate();
                    splitPane.repaint();
                });
            } else {
                // Multiple pages — tabbed display
                JTabbedPane pdfTabs = new JTabbedPane();
                pdfTabs.setTabPlacement(JTabbedPane.TOP);

                for (int i = 0; i < pageCount; i++) {
                    ImageIcon renderedPage = new ImageIcon(pdfRenderer.renderImageWithDPI(i, 75));
                    JLabel pdfView = new JLabel(renderedPage);
                    JScrollPane scrollPane = new JScrollPane(pdfView);
                    scrollPane.getVerticalScrollBar().setUnitIncrement(20);
                    scrollPane.getHorizontalScrollBar().setUnitIncrement(20);
                    pdfTabs.addTab("Seite " + (i + 1), scrollPane);
                }

                configurePdfDropTarget(pdfTabs, frame, statusBar, splitPane);

                SwingUtilities.invokeLater(() -> {
                    splitPane.setLeftComponent(pdfTabs);
                    splitPane.setDividerLocation(512);
                    splitPane.revalidate();
                    splitPane.repaint();
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(splitPane, "Fehler beim Laden der PDF-Datei: " + e.getMessage(), "Fehler",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
