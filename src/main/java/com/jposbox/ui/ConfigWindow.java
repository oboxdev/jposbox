package com.jposbox.ui;

import com.github.anastaciocintra.escpos.EscPosConst.Justification;
import com.github.anastaciocintra.escpos.Style;
import com.jposbox.config.AppConfig;
import com.jposbox.config.PrinterConfig;
import com.jposbox.printer.PrinterManager;
import com.jposbox.server.ApiServer;
import com.jposbox.startup.AutoStart;
import com.jposbox.update.UpdateChecker;
import com.jposbox.update.UpdateInfo;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/** Main configuration window: printer list + server settings + log viewer. */
public class ConfigWindow extends JFrame {

    private final AppConfig config;
    private final PrinterManager printerManager;
    private final ApiServer apiServer;

    private final PrinterTableModel tableModel;
    private final JTable table;

    private final JSpinner httpPortSpinner;
    private final JSpinner httpsPortSpinner;
    private final JCheckBox httpsEnabledCheck;
    private final JCheckBox runAtStartupCheck;

    private final JTextArea logArea = new JTextArea();

    public ConfigWindow(AppConfig config, PrinterManager printerManager, ApiServer apiServer) {
        super("jPosBox");
        this.config = config;
        this.printerManager = printerManager;
        this.apiServer = apiServer;

        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setLayout(new BorderLayout());

        tableModel = new PrinterTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel printerPanel = new JPanel(new BorderLayout());
        printerPanel.setBorder(BorderFactory.createTitledBorder("Printers"));
        printerPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        printerPanel.add(buildPrinterButtons(), BorderLayout.SOUTH);

        httpPortSpinner = new JSpinner(new SpinnerNumberModel(config.httpPort, 1, 65535, 1));
        httpsPortSpinner = new JSpinner(new SpinnerNumberModel(config.httpsPort, 1, 65535, 1));
        httpsEnabledCheck = new JCheckBox("Enable HTTPS", config.httpsEnabled);
        runAtStartupCheck = new JCheckBox("Launch jPosBox at login", config.runAtStartup);

        JPanel settingsPanel = buildSettingsPanel();

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Logs"));
        JButton refreshLogs = new JButton("Refresh logs");
        refreshLogs.addActionListener(e -> loadLogs());
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(logScroll, BorderLayout.CENTER);
        logPanel.add(refreshLogs, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Printers", printerPanel);
        tabs.addTab("Server", settingsPanel);
        tabs.addTab("Logs", logPanel);
        tabs.addTab("About", buildAboutPanel());

        add(tabs, BorderLayout.CENTER);

        setSize(640, 480);
        loadLogs();
    }

    private JPanel buildPrinterButtons() {
        JButton add = new JButton("Add");
        JButton edit = new JButton("Edit");
        JButton remove = new JButton("Remove");
        JButton setDefault = new JButton("Set Default");
        JButton testPrint = new JButton("Test Print");
        JButton openDrawer = new JButton("Open Drawer");

        add.addActionListener(e -> {
            PrinterConfig p = new PrinterDialog(this, null).showDialog();
            if (p != null) {
                if (config.printers.isEmpty()) {
                    p.isDefault = true;
                }
                config.printers.add(p);
                config.save();
                tableModel.fireTableDataChanged();
            }
        });

        edit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            PrinterConfig existing = config.printers.get(row);
            PrinterConfig updated = new PrinterDialog(this, existing).showDialog();
            if (updated != null) {
                updated.isDefault = existing.isDefault;
                config.printers.set(row, updated);
                config.save();
                tableModel.fireTableDataChanged();
            }
        });

        remove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            config.printers.remove(row);
            config.save();
            tableModel.fireTableDataChanged();
        });

        setDefault.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            for (PrinterConfig p : config.printers) {
                p.isDefault = false;
            }
            config.printers.get(row).isDefault = true;
            config.save();
            tableModel.fireTableDataChanged();
        });

        testPrint.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this, "Select a printer first.");
                return;
            }
            PrinterConfig printer = config.printers.get(row);
            try {
                printerManager.print(printer, escpos -> {
                    escpos.initializePrinter();
                    escpos.writeLF(new Style().setJustification(Justification.Center).setBold(true), "jPosBox");
                    escpos.writeLF("Test print: " + printer.name);
                    escpos.writeLF("Connection OK");
                });
                JOptionPane.showMessageDialog(this, "Test ticket sent to " + printer.name);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Print failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        openDrawer.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this, "Select a printer first.");
                return;
            }
            PrinterConfig printer = config.printers.get(row);
            try {
                printerManager.openDrawer(printer);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.add(add);
        panel.add(edit);
        panel.add(remove);
        panel.add(setDefault);
        panel.add(testPrint);
        panel.add(openDrawer);
        return panel;
    }

    private JPanel buildSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;

        panel.add(new JLabel("HTTP port:"), c);
        c.gridx = 1;
        panel.add(httpPortSpinner, c);

        c.gridx = 0;
        c.gridy++;
        panel.add(httpsEnabledCheck, c);

        c.gridx = 0;
        c.gridy++;
        panel.add(new JLabel("HTTPS port:"), c);
        c.gridx = 1;
        panel.add(httpsPortSpinner, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        panel.add(runAtStartupCheck, c);
        c.gridwidth = 1;

        JButton save = new JButton("Save & Restart Server");
        save.addActionListener(e -> {
            config.httpPort = (Integer) httpPortSpinner.getValue();
            config.httpsPort = (Integer) httpsPortSpinner.getValue();
            config.httpsEnabled = httpsEnabledCheck.isSelected();
            config.runAtStartup = runAtStartupCheck.isSelected();
            config.save();
            AutoStart.apply(config.runAtStartup);
            try {
                apiServer.restart();
                JOptionPane.showMessageDialog(this, "Server restarted.");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Restart failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        panel.add(save, c);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    private JPanel buildAboutPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;

        panel.add(new JLabel("jPosBox version:"), c);
        c.gridx = 1;
        panel.add(new JLabel(UpdateChecker.getCurrentVersion()), c);

        JTextField updateUrlField = new JTextField(config.updateCheckUrl, 30);
        c.gridx = 0;
        c.gridy++;
        panel.add(new JLabel("Update check URL:"), c);
        c.gridx = 1;
        panel.add(updateUrlField, c);

        JLabel resultLabel = new JLabel(" ");
        JButton checkButton = new JButton("Check for updates");
        checkButton.addActionListener(e -> {
            config.updateCheckUrl = updateUrlField.getText().trim();
            config.save();
            resultLabel.setText("Checking...");
            new SwingWorker<Optional<UpdateInfo>, Void>() {
                @Override
                protected Optional<UpdateInfo> doInBackground() {
                    return UpdateChecker.checkForUpdate(config.updateCheckUrl, UpdateChecker.getCurrentVersion());
                }

                @Override
                protected void done() {
                    try {
                        Optional<UpdateInfo> update = get();
                        if (update.isPresent()) {
                            UpdateInfo info = update.get();
                            resultLabel.setText("<html>New version available: <b>" + info.version + "</b>"
                                    + (info.url != null ? " — <a href=\"" + info.url + "\">" + info.url + "</a>" : "")
                                    + (info.notes != null ? "<br>" + info.notes : "") + "</html>");
                        } else {
                            resultLabel.setText("You're up to date (" + UpdateChecker.getCurrentVersion() + ").");
                        }
                    } catch (Exception ex) {
                        resultLabel.setText("Check failed: " + ex.getMessage());
                    }
                }
            }.execute();
        });

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        panel.add(checkButton, c);

        c.gridy++;
        panel.add(resultLabel, c);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    private void loadLogs() {
        try {
            var logFile = AppConfig.homeDir().resolve("logs").resolve("app.log");
            if (Files.exists(logFile)) {
                List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - 500);
                logArea.setText(String.join("\n", lines.subList(from, lines.size())));
                logArea.setCaretPosition(logArea.getDocument().getLength());
            } else {
                logArea.setText("(no log file yet)");
            }
        } catch (IOException e) {
            logArea.setText("Failed to read logs: " + e.getMessage());
        }
    }

    private class PrinterTableModel extends AbstractTableModel {
        private final String[] columns = {"Name", "Type", "Connection", "Default", "Cut", "Drawer"};

        @Override
        public int getRowCount() {
            return config.printers.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PrinterConfig p = config.printers.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> p.name;
                case 1 -> p.type.toString();
                case 2 -> p.type == PrinterConfig.Type.NETWORK ? p.host + ":" + p.port : p.systemPrinterName;
                case 3 -> p.isDefault;
                case 4 -> p.cutAfterPrint;
                case 5 -> p.openDrawerAfterPrint;
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex >= 3) {
                return Boolean.class;
            }
            return String.class;
        }
    }
}
