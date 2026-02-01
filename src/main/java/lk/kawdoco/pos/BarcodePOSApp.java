package lk.kawdoco.pos;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * BarcodePOSApp
 * - Designed for USB barcode scanners that behave like a keyboard (wedge mode)
 * - Scan -> lookup product in CSV -> preview -> print label
 *
 * Product file: src/main/resources/products.csv (loaded from classpath)
 *
 * You can also place a "products.csv" next to where you run the app
 * (same format) and it will be loaded first (handy for quick updates).
 */
public class BarcodePOSApp extends JFrame {

    // ---- Change these to match your label paper (mm) ----
    // Common thermal labels: 58x40mm, 50x30mm, 100x50mm etc.
    private static final double LABEL_W_MM = 58;
    private static final double LABEL_H_MM = 40;

    // If your scanner sends TAB instead of ENTER, set true
    private static final boolean ACCEPT_TAB_AS_END = false;

    // Barcode format for printing (Code128 works well for SKUs)
    private static final BarcodeFormat PRINT_FORMAT = BarcodeFormat.CODE_128;

    private final JTextField scanField = new JTextField(26);
    private final JTextField codeField = new JTextField(26);
    private final JTextField nameField = new JTextField(26);
    private final JTextField priceField = new JTextField(10);

    private final JTextArea logArea = new JTextArea(10, 60);
    private final JLabel status = new JLabel("Ready. Click scan box and scan.");
    private final PreviewPanel previewPanel = new PreviewPanel();

    private BufferedImage lastLabelImage;

    private Map<String, Product> products = new HashMap<>();

    public BarcodePOSApp() {
        super("Barcode Scanner + Product Lookup + Print (macOS Java)");

        // UI font (avoids Times font warning on some macOS setups)
        Font uiFont = new Font("SansSerif", Font.PLAIN, 14);
        UIManager.put("Label.font", uiFont);
        UIManager.put("TextField.font", uiFont);
        UIManager.put("TextArea.font", uiFont);
        UIManager.put("Button.font", uiFont);

        products = loadProducts();

        // fields config
        codeField.setEditable(false);
        nameField.setEditable(false);
        priceField.setEditable(false);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JButton reloadBtn = new JButton("Reload products");
        reloadBtn.addActionListener(e -> {
            products = loadProducts();
            status.setText("Products loaded: " + products.size());
            scanField.requestFocusInWindow();
        });

        JButton clearBtn = new JButton("Clear log");
        clearBtn.addActionListener(e -> logArea.setText(""));

        JButton copyBtn = new JButton("Copy log");
        copyBtn.addActionListener(e -> {
            logArea.selectAll();
            logArea.copy();
            status.setText("Copied log to clipboard.");
        });

        JButton printBtn = new JButton("Print label");
        printBtn.addActionListener(e -> printLabel());

        JButton previewBtn = new JButton("Generate preview");
        previewBtn.addActionListener(e -> generatePreview());

        JPanel left = new JPanel(new GridBagLayout());
        left.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        int row = 0;
        c.gridx = 0; c.gridy = row; left.add(new JLabel("Scan here:"), c);
        c.gridx = 1; c.gridy = row; left.add(scanField, c);

        row++;
        c.gridx = 0; c.gridy = row; left.add(new JLabel("Code:"), c);
        c.gridx = 1; c.gridy = row; left.add(codeField, c);

        row++;
        c.gridx = 0; c.gridy = row; left.add(new JLabel("Product:"), c);
        c.gridx = 1; c.gridy = row; left.add(nameField, c);

        row++;
        c.gridx = 0; c.gridy = row; left.add(new JLabel("Price:"), c);
        c.gridx = 1; c.gridy = row; left.add(priceField, c);

        row++;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(previewBtn);
        buttons.add(printBtn);
        buttons.add(reloadBtn);
        buttons.add(clearBtn);
        buttons.add(copyBtn);

        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        left.add(buttons, c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Scan log"));
        left.add(logScroll, c);
        c.gridwidth = 1;

        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        left.add(status, c);

        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createTitledBorder("Label preview"));
        right.add(previewPanel, BorderLayout.CENTER);

        setLayout(new GridLayout(1, 2));
        add(left);
        add(right);

        // scanning behavior
        scanField.addActionListener(e -> onScan(scanField.getText())); // ENTER finishes scan
        scanField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (ACCEPT_TAB_AS_END && e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    onScan(scanField.getText());
                }
            }
        });

        // Focus scan field when window focuses
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                SwingUtilities.invokeLater(() -> scanField.requestFocusInWindow());
            }
        });

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 520);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> scanField.requestFocusInWindow());
        status.setText("Products loaded: " + products.size() + ". Ready to scan.");
    }

    private void onScan(String raw) {
        String scanned = raw == null ? "" : raw.trim();
        scanField.setText("");

        if (scanned.isEmpty()) {
            status.setText("Empty scan ignored.");
            return;
        }

        Product p = products.get(scanned);

        codeField.setText(scanned);

        if (p != null) {
            nameField.setText(p.name);
            priceField.setText(p.price);
            status.setText("Found: " + p.name);
        } else {
            nameField.setText("(NOT FOUND)");
            priceField.setText("");
            status.setText("Product not found for code: " + scanned);
        }

        appendLog(scanned, p);

        // Auto preview after each scan
        generatePreview();

        scanField.requestFocusInWindow();
    }

    private void appendLog(String code, Product p) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String line = (p == null)
                ? String.format("%s | %s | NOT_FOUND%n", ts, code)
                : String.format("%s | %s | %s | %s%n", ts, code, p.name, p.price);
        logArea.append(line);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void generatePreview() {
        String code = codeField.getText().trim();
        String name = nameField.getText().trim();
        String price = priceField.getText().trim();

        if (code.isEmpty()) {
            previewPanel.setImage(null);
            lastLabelImage = null;
            return;
        }

        try {
            lastLabelImage = renderLabelImage(code, name, price);
            previewPanel.setImage(lastLabelImage);
        } catch (Exception ex) {
            status.setText("Preview failed: " + ex.getMessage());
            lastLabelImage = null;
            previewPanel.setImage(null);
        }
    }

    private void printLabel() {
        if (lastLabelImage == null) {
            generatePreview();
            if (lastLabelImage == null) {
                JOptionPane.showMessageDialog(this, "No label to print. Scan first.");
                return;
            }
        }

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Barcode Label");

        Printable printable = (graphics, pageFormat, pageIndex) -> {
            if (pageIndex > 0) return Printable.NO_SUCH_PAGE;

            Graphics2D g2 = (Graphics2D) graphics;
            g2.translate(pageFormat.getImageableX(), pageFormat.getImageableY());

            double pw = pageFormat.getImageableWidth();
            double ph = pageFormat.getImageableHeight();

            double sx = pw / lastLabelImage.getWidth();
            double sy = ph / lastLabelImage.getHeight();
            double scale = Math.min(sx, sy);

            int drawW = (int) Math.round(lastLabelImage.getWidth() * scale);
            int drawH = (int) Math.round(lastLabelImage.getHeight() * scale);

            int x = (int) Math.round((pw - drawW) / 2);
            int y = (int) Math.round((ph - drawH) / 2);

            g2.drawImage(lastLabelImage, x, y, drawW, drawH, null);
            return Printable.PAGE_EXISTS;
        };

        try {
            PageFormat pf = job.defaultPage();
            Paper paper = new Paper();

            double wPt = mmToPoints(LABEL_W_MM);
            double hPt = mmToPoints(LABEL_H_MM);

            paper.setSize(wPt, hPt);

            double marginPt = mmToPoints(2);
            paper.setImageableArea(marginPt, marginPt, wPt - 2 * marginPt, hPt - 2 * marginPt);

            pf.setPaper(paper);
            pf.setOrientation(PageFormat.PORTRAIT);

            job.setPrintable(printable, pf);

            if (job.printDialog()) {
                job.print();
                status.setText("Print sent.");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Print failed: " + ex.getMessage());
        }
    }

    private static double mmToPoints(double mm) {
        return (mm / 25.4) * 72.0;
    }

    private static BufferedImage renderLabelImage(String code, String name, String price) throws Exception {
        int dpi = 203; // common thermal printer DPI
        int wPx = (int) Math.round((LABEL_W_MM / 25.4) * dpi);
        int hPx = (int) Math.round((LABEL_H_MM / 25.4) * dpi);

        BufferedImage img = new BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, wPx, hPx);

        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int pad = Math.max(10, wPx / 40);

        // Header line: Product name (left)
        Font nameFont = new Font("SansSerif", Font.BOLD, Math.max(14, hPx / 10));
        g.setFont(nameFont);
        String topLine = (name == null || name.isBlank()) ? "" : name;
        drawTrimmedText(g, topLine, pad, pad + g.getFontMetrics().getAscent(), wPx - 2 * pad);

        // Price (right)
        Font priceFont = new Font("SansSerif", Font.BOLD, Math.max(14, hPx / 10));
        g.setFont(priceFont);
        if (price != null && !price.isBlank() && !"(NOT FOUND)".equalsIgnoreCase(topLine)) {
            String p = price.startsWith("Rs") ? price : ("Rs " + price);
            int sw = g.getFontMetrics().stringWidth(p);
            g.drawString(p, wPx - pad - sw, pad + g.getFontMetrics().getAscent());
        } else if (price != null && !price.isBlank()) {
            String p = price.startsWith("Rs") ? price : ("Rs " + price);
            int sw = g.getFontMetrics().stringWidth(p);
            g.drawString(p, wPx - pad - sw, pad + g.getFontMetrics().getAscent());
        }

        // Barcode area
        int barcodeTop = (int) (hPx * 0.28);
        int barcodeH = (int) (hPx * 0.50);
        int barcodeW = wPx - 2 * pad;

        BufferedImage barcode = makeBarcode(code, barcodeW, barcodeH, PRINT_FORMAT);
        int bx = (wPx - barcode.getWidth()) / 2;
        int by = barcodeTop;
        g.drawImage(barcode, bx, by, null);

        // Bottom: code
        Font codeFont = new Font("SansSerif", Font.PLAIN, Math.max(12, hPx / 11));
        g.setFont(codeFont);
        int codeY = hPx - pad;
        int codeSW = g.getFontMetrics().stringWidth(code);
        g.drawString(code, (wPx - codeSW) / 2, codeY);

        g.dispose();
        return img;
    }

    private static BufferedImage makeBarcode(String text, int width, int height, BarcodeFormat format) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(text, format, width, height);
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    private static void drawTrimmedText(Graphics2D g, String text, int x, int y, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        String t = text == null ? "" : text;
        while (!t.isEmpty() && fm.stringWidth(t) > maxWidth) {
            t = t.substring(0, t.length() - 1);
        }
        if (!t.equals(text) && t.length() > 3) t = t.substring(0, t.length() - 3) + "...";
        g.drawString(t, x, y);
    }

    private Map<String, Product> loadProducts() {
        // Priority 1: external products.csv in current working directory
        File external = new File("products.csv");
        try {
            if (external.exists() && external.isFile()) {
                return readProductsCsv(new FileInputStream(external));
            }
        } catch (Exception ignored) { }

        // Priority 2: classpath resource
        try (InputStream in = BarcodePOSApp.class.getClassLoader().getResourceAsStream("products.csv")) {
            if (in == null) return new HashMap<>();
            return readProductsCsv(in);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private Map<String, Product> readProductsCsv(InputStream inputStream) throws IOException {
        Map<String, Product> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String header = br.readLine(); // code,name,price
            if (header == null) return map;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                // Simple CSV split (works for basic files). If you need commas inside names, tell me.
                String[] parts = line.split(",", -1);
                if (parts.length < 2) continue;
                String code = parts[0].trim();
                String name = parts[1].trim();
                String price = parts.length >= 3 ? parts[2].trim() : "";
                if (!code.isEmpty()) map.put(code, new Product(code, name, price));
            }
        }
        return map;
    }

    private static class Product {
        final String code;
        final String name;
        final String price;
        Product(String code, String name, String price) {
            this.code = code;
            this.name = name;
            this.price = price;
        }
    }

    private static class PreviewPanel extends JPanel {
        private BufferedImage image;

        public void setImage(BufferedImage img) {
            this.image = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) return;

            int pw = getWidth();
            int ph = getHeight();
            double sx = (double) pw / image.getWidth();
            double sy = (double) ph / image.getHeight();
            double s = Math.min(sx, sy);

            int w = (int) Math.round(image.getWidth() * s);
            int h = (int) Math.round(image.getHeight() * s);

            int x = (pw - w) / 2;
            int y = (ph - h) / 2;

            g.drawImage(image, x, y, w, h, null);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new BarcodePOSApp().setVisible(true));
    }
}
