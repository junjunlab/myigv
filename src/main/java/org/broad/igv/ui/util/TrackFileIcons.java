package org.broad.igv.ui.util;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small 16x16 icons for the remote file browser. Shapes differ per data family so the list is
 * readable without relying on colour alone; folders get the familiar yellow look.
 */
public final class TrackFileIcons {

    private enum Kind {
        READS, INDEX, SIGNAL, VARIANT, FEATURE, MATRIX, SEQUENCE, ARCHIVE, GENERIC
    }

    private static final Map<String, Icon> CACHE = new HashMap<>();
    private static Icon folderIcon;

    private TrackFileIcons() {
    }

    public static Icon forEntry(String name, boolean directory) {
        if (directory) {
            return folder();
        }
        return forName(name);
    }

    public static Icon forName(String name) {
        String suffix = suffixOf(name == null ? "" : name);
        return CACHE.computeIfAbsent(suffix, s -> build(kindOf(s)));
    }

    public static synchronized Icon folder() {
        if (folderIcon == null) {
            folderIcon = buildFolder();
        }
        return folderIcon;
    }

    private static String suffixOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String compound : new String[]{".vcf.gz", ".bed.gz", ".gtf.gz", ".fa.gz", ".bedpe.gz"}) {
            if (lower.endsWith(compound)) {
                return compound;
            }
        }
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot);
    }

    private static Kind kindOf(String suffix) {
        switch (suffix) {
            case ".bam":
            case ".cram":
                return Kind.READS;
            case ".bai":
            case ".crai":
            case ".csi":
            case ".tbi":
                return Kind.INDEX;
            case ".bw":
            case ".bigwig":
            case ".bedgraph":
            case ".wig":
            case ".tdf":
            case ".seg":
                return Kind.SIGNAL;
            case ".vcf":
            case ".vcf.gz":
                return Kind.VARIANT;
            case ".bed":
            case ".bedpe":
            case ".bed.gz":
            case ".bedpe.gz":
            case ".gtf":
            case ".gtf.gz":
            case ".gff":
            case ".gff3":
            case ".narrowpeak":
            case ".broadpeak":
                return Kind.FEATURE;
            case ".hic":
            case ".cool":
            case ".mcool":
            case ".h5":
                return Kind.MATRIX;
            case ".fa":
            case ".fasta":
            case ".fna":
            case ".fa.gz":
                return Kind.SEQUENCE;
            case ".gz":
            case ".zip":
            case ".tar":
            case ".bz2":
            case ".xz":
            case ".tgz":
                return Kind.ARCHIVE;
            default:
                return Kind.GENERIC;
        }
    }

    private static Color colorOf(Kind kind) {
        switch (kind) {
            case READS:
                return new Color(0x2F6FD0);
            case INDEX:
                return new Color(0x8A8A8A);
            case SIGNAL:
                return new Color(0x2E9E5B);
            case VARIANT:
                return new Color(0xE08A2E);
            case FEATURE:
                return new Color(0x7B5BD6);
            case MATRIX:
                return new Color(0xC0392B);
            case SEQUENCE:
                return new Color(0x17A2B8);
            case ARCHIVE:
                return new Color(0x8B5E3C);
            default:
                return new Color(0x9AA0A6);
        }
    }

    private static Icon build(Kind kind) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(Color.WHITE);
        g.fillRoundRect(2, 1, 11, 14, 2, 2);
        g.setColor(new Color(0x9AA0A6));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(2, 1, 11, 14, 2, 2);

        g.setColor(new Color(0xE8EAED));
        g.fillPolygon(new int[]{8, 13, 13}, new int[]{1, 1, 6}, 3);
        g.setColor(new Color(0x9AA0A6));
        g.drawLine(8, 1, 13, 6);

        drawGlyph(g, kind, colorOf(kind));
        g.dispose();
        return new ImageIcon(img);
    }

    private static void drawGlyph(Graphics2D g, Kind kind, Color accent) {
        g.setColor(accent);
        switch (kind) {
            case READS:
                g.fillRect(5, 7, 6, 1);
                g.fillRect(5, 9, 5, 1);
                g.fillRect(5, 11, 6, 1);
                break;
            case INDEX:
                g.fillRect(5, 7, 1, 6);
                g.fillRect(7, 8, 4, 1);
                g.fillRect(7, 10, 3, 1);
                g.fillRect(7, 12, 4, 1);
                break;
            case SIGNAL:
                g.setStroke(new BasicStroke(1.4f));
                g.drawPolyline(new int[]{4, 6, 8, 10, 12}, new int[]{10, 7, 11, 8, 10}, 5);
                break;
            case VARIANT:
                g.fillRect(8, 7, 2, 6);
                g.fillRect(6, 8, 1, 1);
                g.fillRect(11, 10, 1, 1);
                break;
            case FEATURE:
                g.fillRect(5, 9, 6, 1);
                g.fillRect(5, 7, 1, 5);
                g.fillRect(10, 7, 1, 5);
                break;
            case MATRIX:
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        if ((i + j) % 2 == 0) {
                            g.fillRect(4 + i * 3, 7 + j * 2, 2, 1);
                        }
                    }
                }
                break;
            case SEQUENCE:
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                g.drawString("A", 5, 13);
                break;
            case ARCHIVE:
                for (int y = 7; y < 13; y += 2) {
                    g.fillRect(7, y, 2, 1);
                }
                break;
            default:
                g.fillRect(5, 9, 6, 1);
                break;
        }
    }

    private static Icon buildFolder() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        Color body = new Color(0xF9C74F);
        Color edge = new Color(0xD9A521);

        g.setColor(body);
        g.fillRoundRect(1, 4, 14, 10, 2, 2);
        g.setColor(edge);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(1, 4, 14, 10, 2, 2);

        g.setColor(body);
        g.fillRoundRect(1, 2, 7, 4, 2, 2);
        g.setColor(edge);
        g.drawRoundRect(1, 2, 7, 4, 2, 2);

        g.dispose();
        return new ImageIcon(img);
    }
}
