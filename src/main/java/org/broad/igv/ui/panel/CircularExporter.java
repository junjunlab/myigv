package org.broad.igv.ui.panel;

import org.apache.batik.dom.GenericDOMImplementation;
import org.apache.batik.svggen.SVGGraphics2D;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Export the Circular View to a raster (PNG) or vector (SVG, PDF) file.
 *
 * <p>Both the pixel size and the resolution are expressed the way a publication figure is
 * normally specified: {@code width x height} is the number of pixels the figure is laid out
 * on, and {@code dpi} turns that into a physical size ({@code w / dpi} inches). For PNG the
 * dpi is written into the {@code pHYs} chunk, so Word / PowerPoint / Illustrator insert the
 * image at the correct physical size; for PDF it becomes the page size. SVG is resolution
 * independent and keeps the pixel geometry plus an equivalent physical size.
 *
 * <p>All three formats render through {@link CircularViewPanel#renderTo(Graphics2D, int, int)},
 * i.e. the exact same drawing code as the on-screen view.
 */
public final class CircularExporter {

    /** Output formats offered in the Export menu. */
    public enum Format {
        PNG("PNG image", "png"),
        SVG("SVG vector", "svg"),
        PDF("PDF vector", "pdf");

        public final String label;
        public final String extension;

        Format(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        public String toString() { return label; }
    }

    /** Smallest / largest sensible output edge, in pixels. */
    private static final int MIN_EDGE = 100;
    private static final int MAX_EDGE = 20000;

    // Remembered between exports within a session.
    private static int lastWidth = 0, lastHeight = 0, lastDpi = 300;
    private static File lastDir = null;

    private CircularExporter() { }

    /**
     * Write the current circular view to {@code file}.
     *
     * @param panel source panel
     * @param file  destination file (written / overwritten)
     * @param fmt   output format
     * @param w     output width in pixels
     * @param h     output height in pixels
     * @param dpi   resolution used for PNG metadata and the PDF page size
     */
    public static void export(CircularViewPanel panel, File file, Format fmt,
                              int w, int h, int dpi) throws Exception {
        w = Math.max(MIN_EDGE, w);
        h = Math.max(MIN_EDGE, h);
        dpi = Math.max(1, dpi);
        switch (fmt) {
            case PNG: exportPng(panel, file, w, h, dpi); break;
            case SVG: exportSvg(panel, file, w, h, dpi); break;
            case PDF: exportPdf(panel, file, w, h, dpi); break;
            default: throw new IllegalArgumentException("Unknown format: " + fmt);
        }
    }

    // ------------------------------------------------------------------
    //  PNG
    // ------------------------------------------------------------------

    private static void exportPng(CircularViewPanel panel, File file, int w, int h, int dpi)
            throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(panel.getBackground() == null ? Color.WHITE : panel.getBackground());
            g2.fillRect(0, 0, w, h);
            panel.renderTo(g2, w, h);
        } finally {
            g2.dispose();
        }
        writePng(img, file, dpi);
    }

    /** Encode a PNG and record the resolution in its {@code pHYs} chunk (1 m = 1000 mm). */
    private static void writePng(BufferedImage img, File file, int dpi) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata md = writer.getDefaultImageMetadata(
                    new ImageTypeSpecifier(img), param);
            IIOMetadata out = md;
            try {
                String f = "javax_imageio_png_1.0";
                org.w3c.dom.Node root = md.getAsTree(f);
                NodeList phys = ((Element) root).getElementsByTagName("pHYs");
                for (int i = phys.getLength() - 1; i >= 0; i--) {
                    root.removeChild(phys.item(i));
                }
                Element px = newNode(root, "pHYs");
                int ppm = (int) Math.round(dpi / 0.0254);   // dots per metre
                px.setAttribute("pixelsPerUnitXAxis", String.valueOf(ppm));
                px.setAttribute("pixelsPerUnitYAxis", String.valueOf(ppm));
                px.setAttribute("unitSpecifier", "meter");
                // pHYs is a header chunk: keep it directly after IHDR rather than at the end of
                // the tree, which is the order the PNG native metadata format expects.
                if (root.getFirstChild() != null) {
                    root.insertBefore(px, root.getFirstChild().getNextSibling());
                } else {
                    root.appendChild(px);
                }
                md.setFromTree(f, root);
                out = md;
            } catch (Exception ignore) {
                // Resolution metadata is cosmetic; never fail an export over it.
                out = null;
            }
            writer.write(null, new IIOImage(img, null, out), param);
        } finally {
            writer.dispose();
        }
    }

    /** Create a metadata node: ImageIO's {@code IIOMetadataNode} is not owned by a Document. */
    private static Element newNode(org.w3c.dom.Node like, String name) {
        if (like instanceof javax.imageio.metadata.IIOMetadataNode) {
            return new javax.imageio.metadata.IIOMetadataNode(name);
        }
        org.w3c.dom.Document doc = like.getOwnerDocument();
        if (doc != null) return doc.createElement(name);
        return new javax.imageio.metadata.IIOMetadataNode(name);
    }

    // ------------------------------------------------------------------
    //  SVG (Batik)
    // ------------------------------------------------------------------

    private static void exportSvg(CircularViewPanel panel, File file, int w, int h, int dpi)
            throws Exception {
        DOMImplementation domImpl = GenericDOMImplementation.getDOMImplementation();
        Document doc = domImpl.createDocument("http://www.w3.org/2000/svg", "svg", null);
        SVGGraphics2D svg = new SVGGraphics2D(doc);
        svg.setSVGCanvasSize(new Dimension(w, h));
        svg.setColor(panel.getBackground() == null ? Color.WHITE : panel.getBackground());
        svg.fillRect(0, 0, w, h);
        panel.renderTo(svg, w, h);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file),
                StandardCharsets.UTF_8)) {
            svg.stream(out, true);
        }
        svg.dispose();
    }

    // ------------------------------------------------------------------
    //  PDF (OpenPDF PdfGraphics2D: a Graphics2D that emits PDF operators)
    // ------------------------------------------------------------------

    private static void exportPdf(CircularViewPanel panel, File file, int w, int h, int dpi)
            throws Exception {
        // Page size in PostScript points (1 inch = 72 pt), so the figure has the physical
        // size implied by w px at the requested dpi.
        float pageW = (float) (w * 72.0 / dpi);
        float pageH = (float) (h * 72.0 / dpi);
        com.lowagie.text.Document doc = new com.lowagie.text.Document(
                new com.lowagie.text.Rectangle(pageW, pageH), 0, 0, 0, 0);
        FileOutputStream fos = new FileOutputStream(file);
        try {
            com.lowagie.text.pdf.PdfWriter writer =
                    com.lowagie.text.pdf.PdfWriter.getInstance(doc, fos);
            doc.open();
            com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
            com.lowagie.text.pdf.PdfGraphics2D g2 =
                    new com.lowagie.text.pdf.PdfGraphics2D(cb, pageW, pageH);
            try {
                // Draw in the same pixel coordinate system the screen uses, then let the
                // page transform scale it to the physical page.
                g2.scale(pageW / (double) w, pageH / (double) h);
                g2.setColor(panel.getBackground() == null ? Color.WHITE : panel.getBackground());
                g2.fillRect(0, 0, w, h);
                panel.renderTo(g2, w, h);
            } finally {
                g2.dispose();
            }
            doc.close();
        } finally {
            fos.close();
        }
    }

    // ------------------------------------------------------------------
    //  Export dialog
    // ------------------------------------------------------------------

    /**
     * Show the export dialog for the circular view, with {@code initial} preselected.
     *
     * @return the written file, or {@code null} if the user cancelled
     */
    public static File showDialog(Component parent, CircularViewPanel panel, Format initial) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(owner, "Export circular view",
                Dialog.ModalityType.APPLICATION_MODAL);

        JComboBox<Format> fmtBox = new JComboBox<>(Format.values());
        fmtBox.setSelectedItem(initial == null ? Format.PNG : initial);

        int defW = lastWidth > 0 ? lastWidth : currentWidth(panel);
        int defH = lastHeight > 0 ? lastHeight : currentHeight(panel);
        JTextField wField = new JTextField(String.valueOf(defW), 6);
        JTextField hField = new JTextField(String.valueOf(defH), 6);
        JTextField dpiField = new JTextField(String.valueOf(lastDpi), 6);

        JTextField pathField = new JTextField(24);
        JButton browse = new JButton("Browse…");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(10, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;

        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        form.add(new JLabel("Format:"), c);
        c.gridx = 1; c.gridwidth = 3; form.add(fmtBox, c);
        row++;

        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        form.add(new JLabel("Width (px):"), c);
        c.gridx = 1; form.add(wField, c);
        c.gridx = 2; form.add(new JLabel("Height (px):"), c);
        c.gridx = 3; form.add(hField, c);
        row++;

        c.gridx = 0; c.gridy = row;
        form.add(new JLabel("Resolution (dpi):"), c);
        c.gridx = 1; form.add(dpiField, c);
        JLabel phys = new JLabel(" ");
        phys.setForeground(Color.GRAY);
        c.gridx = 2; c.gridwidth = 2; form.add(phys, c);
        row++;

        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        form.add(new JLabel("File:"), c);
        c.gridx = 1; c.gridwidth = 2; form.add(pathField, c);
        c.gridx = 3; c.gridwidth = 1; form.add(browse, c);

        JButton resetSize = new JButton("Use current window size");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton cancel = new JButton("Cancel");
        JButton ok = new JButton("Export");
        south.add(resetSize);
        south.add(Box.createHorizontalStrut(10));
        south.add(cancel);
        south.add(ok);

        dlg.getContentPane().add(form, BorderLayout.CENTER);
        dlg.getContentPane().add(south, BorderLayout.SOUTH);
        dlg.getRootPane().setDefaultButton(ok);

        final File[] result = {null};

        Runnable syncExt = () -> {
            Format f = (Format) fmtBox.getSelectedItem();
            if (f == null) return;
            String p = pathField.getText().trim();
            if (p.isEmpty()) {
                pathField.setText(defaultName(f));
            } else {
                int dot = p.lastIndexOf('.');
                int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
                if (dot > slash) p = p.substring(0, dot);
                if (!p.toLowerCase(Locale.ROOT).endsWith("." + f.extension)) {
                    p = p + "." + f.extension;
                }
                pathField.setText(p);
            }
            boolean raster = f != Format.SVG;
            dpiField.setEnabled(raster);
            updatePhysicalSize(phys, wField, hField, dpiField);
        };

        fmtBox.addActionListener(e -> syncExt.run());
        Runnable revalidate = () -> updatePhysicalSize(phys, wField, hField, dpiField);
        wField.getDocument().addDocumentListener(new SimpleDocListener(revalidate));
        hField.getDocument().addDocumentListener(new SimpleDocListener(revalidate));
        dpiField.getDocument().addDocumentListener(new SimpleDocListener(revalidate));

        resetSize.addActionListener(e -> {
            wField.setText(String.valueOf(currentWidth(panel)));
            hField.setText(String.valueOf(currentHeight(panel)));
        });

        browse.addActionListener(e -> {
            Format f = (Format) fmtBox.getSelectedItem();
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Export circular view");
            chooser.setFileFilter(new FileNameExtensionFilter(
                    f == null ? "Images" : f.label, f == null ? "png" : f.extension));
            String p = pathField.getText().trim();
            if (!p.isEmpty()) {
                File cur = new File(p);
                chooser.setSelectedFile(cur);
                if (cur.getParentFile() != null && cur.getParentFile().isDirectory()) {
                    chooser.setCurrentDirectory(cur.getParentFile());
                }
            }
            if (chooser.showSaveDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                pathField.setText(chooser.getSelectedFile().getAbsolutePath());
                syncExt.run();
            }
        });

        cancel.addActionListener(e -> dlg.dispose());

        ok.addActionListener(e -> {
            final Format f = (Format) fmtBox.getSelectedItem();
            int w, h, dpi;
            try {
                w = Integer.parseInt(wField.getText().trim());
                h = Integer.parseInt(hField.getText().trim());
                dpi = Integer.parseInt(dpiField.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg,
                        "Width, height and resolution must be whole numbers.",
                        "Export", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (w < MIN_EDGE || h < MIN_EDGE || w > MAX_EDGE || h > MAX_EDGE) {
                JOptionPane.showMessageDialog(dlg,
                        "Width and height must be between " + MIN_EDGE + " and " + MAX_EDGE
                                + " pixels.",
                        "Export", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (dpi < 1 || dpi > 2400) {
                JOptionPane.showMessageDialog(dlg, "Resolution must be between 1 and 2400 dpi.",
                        "Export", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String p = pathField.getText().trim();
            if (p.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, "Choose an output file.",
                        "Export", JOptionPane.WARNING_MESSAGE);
                return;
            }
            File out = new File(p);
            if (out.exists()) {
                int r = JOptionPane.showConfirmDialog(dlg,
                        "Overwrite existing file?\n" + out.getAbsolutePath(),
                        "Export", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (r != JOptionPane.YES_OPTION) return;
            }

            lastWidth = w;
            lastHeight = h;
            lastDpi = dpi;

            Cursor old = dlg.getCursor();
            dlg.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            try {
                export(panel, out, f, w, h, dpi);
                result[0] = out;
                if (out.getParentFile() != null) lastDir = out.getParentFile();
                dlg.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg,
                        "Export failed: " + ex.getClass().getSimpleName()
                                + (ex.getMessage() == null ? "" : "\n" + ex.getMessage()),
                        "Export", JOptionPane.ERROR_MESSAGE);
            } finally {
                dlg.setCursor(old);
            }
        });

        // Seed the default path from the last used directory.
        pathField.setText(defaultName((Format) fmtBox.getSelectedItem()));
        updatePhysicalSize(phys, wField, hField, dpiField);
        dpiField.setEnabled(fmtBox.getSelectedItem() != Format.SVG);

        dlg.pack();
        dlg.setResizable(false);
        dlg.setLocationRelativeTo(parent == null ? panel : parent);
        dlg.setVisible(true);
        return result[0];
    }

    /** Current on-screen width, with a sane fallback if the panel was never laid out. */
    private static int currentWidth(CircularViewPanel panel) {
        int w = panel.getWidth();
        return (w >= 200) ? w : 1000;
    }

    private static int currentHeight(CircularViewPanel panel) {
        int h = panel.getHeight();
        return (h >= 200) ? h : 1000;
    }

    private static String defaultName(Format f) {
        File dir = lastDir != null ? lastDir : new File(System.getProperty("user.home"));
        return new File(dir, "circular_view." + f.extension).getAbsolutePath();
    }

    /** Live "= 5.00 x 5.00 in / 12.70 x 12.70 cm" hint under the size fields. */
    private static void updatePhysicalSize(JLabel label, JTextField wField,
                                           JTextField hField, JTextField dpiField) {
        try {
            int w = Integer.parseInt(wField.getText().trim());
            int h = Integer.parseInt(hField.getText().trim());
            int dpi = Math.max(1, Integer.parseInt(dpiField.getText().trim()));
            double wi = w / (double) dpi, hi = h / (double) dpi;
            label.setText(String.format(Locale.ROOT,
                    "= %.2f x %.2f in  (%.1f x %.1f cm)", wi, hi, wi * 2.54, hi * 2.54));
        } catch (NumberFormatException ignore) {
            label.setText(" ");
        }
    }

    /** Minimal DocumentListener, so the hint refreshes on every keystroke. */
    private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable r;

        SimpleDocListener(Runnable r) { this.r = r; }

        public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }

        public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }

        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
