package com.jposbox.ui;

import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;

import javax.swing.*;
import java.awt.*;

/** Modal dialog for creating/editing a {@link PrinterConfig}. */
public class PrinterDialog extends JDialog {

    private boolean confirmed = false;

    private final JTextField nameField = new JTextField(18);
    private final JComboBox<PrinterConfig.Type> typeCombo = new JComboBox<>(PrinterConfig.Type.values());
    private final JTextField hostField = new JTextField(15);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(9100, 1, 65535, 1));
    private final JComboBox<String> systemPrinterCombo = new JComboBox<>();
    private final JCheckBox cutCheck = new JCheckBox("Cut paper after print", true);
    private final JCheckBox drawerCheck = new JCheckBox("Open cash drawer after print", false);
    private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(42, 20, 80, 1));

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    public PrinterDialog(Window owner, PrinterConfig existing) {
        super(owner, existing == null ? "Add Printer" : "Edit Printer", ModalityType.APPLICATION_MODAL);
        buildUi();
        if (existing != null) {
            populate(existing);
        }
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;

        addRow(form, c, "Name:", nameField);
        addRow(form, c, "Type:", typeCombo);

        // NETWORK card
        JPanel networkPanel = new JPanel(new GridBagLayout());
        GridBagConstraints nc = new GridBagConstraints();
        nc.insets = new Insets(4, 4, 4, 4);
        nc.fill = GridBagConstraints.HORIZONTAL;
        nc.gridx = 0;
        nc.gridy = 0;
        addRow(networkPanel, nc, "Host / IP:", hostField);
        addRow(networkPanel, nc, "Port:", portSpinner);

        // SYSTEM card
        JPanel systemPanel = new JPanel(new GridBagLayout());
        GridBagConstraints sc = new GridBagConstraints();
        sc.insets = new Insets(4, 4, 4, 4);
        sc.fill = GridBagConstraints.HORIZONTAL;
        sc.gridx = 0;
        sc.gridy = 0;
        for (String name : PrinterManager.listSystemPrinters()) {
            systemPrinterCombo.addItem(name);
        }
        systemPrinterCombo.setEditable(true);
        addRow(systemPanel, sc, "System printer:", systemPrinterCombo);

        cards.add(networkPanel, PrinterConfig.Type.NETWORK.name());
        cards.add(systemPanel, PrinterConfig.Type.SYSTEM.name());

        c.gridwidth = 2;
        c.gridx = 0;
        form.add(cards, c);
        c.gridy++;

        c.gridwidth = 1;
        form.add(cutCheck, gbc(c, 0));
        c.gridy++;
        form.add(drawerCheck, gbc(c, 0));
        c.gridy++;
        addRow(form, c, "Chars per line:", widthSpinner);

        typeCombo.addActionListener(e -> cardLayout.show(cards, ((PrinterConfig.Type) typeCombo.getSelectedItem()).name()));
        cardLayout.show(cards, PrinterConfig.Type.NETWORK.name());

        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        okButton.addActionListener(e -> {
            confirmed = true;
            setVisible(false);
        });
        cancelButton.addActionListener(e -> setVisible(false));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelButton);
        buttons.add(okButton);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(okButton);
    }

    private GridBagConstraints gbc(GridBagConstraints template, int x) {
        GridBagConstraints c = (GridBagConstraints) template.clone();
        c.gridx = x;
        return c;
    }

    private void addRow(JPanel panel, GridBagConstraints c, String label, JComponent field) {
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
        c.gridy++;
    }

    private void populate(PrinterConfig p) {
        nameField.setText(p.name);
        typeCombo.setSelectedItem(p.type);
        hostField.setText(p.host == null ? "" : p.host);
        portSpinner.setValue(p.port);
        if (p.systemPrinterName != null) {
            systemPrinterCombo.setSelectedItem(p.systemPrinterName);
        }
        cutCheck.setSelected(p.cutAfterPrint);
        drawerCheck.setSelected(p.openDrawerAfterPrint);
        widthSpinner.setValue(p.charWidth);
        cardLayout.show(cards, p.type.name());
    }

    /** Returns the configured printer, or null if the dialog was cancelled. */
    public PrinterConfig showDialog() {
        setVisible(true);
        if (!confirmed) {
            return null;
        }
        PrinterConfig p = new PrinterConfig();
        p.name = nameField.getText().trim();
        p.type = (PrinterConfig.Type) typeCombo.getSelectedItem();
        p.host = hostField.getText().trim();
        p.port = (Integer) portSpinner.getValue();
        Object sel = systemPrinterCombo.getEditor().getItem();
        p.systemPrinterName = sel == null ? null : sel.toString().trim();
        p.cutAfterPrint = cutCheck.isSelected();
        p.openDrawerAfterPrint = drawerCheck.isSelected();
        p.charWidth = (Integer) widthSpinner.getValue();
        return p;
    }
}
