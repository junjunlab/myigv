package org.broad.igv.update;

import org.broad.igv.Globals;
import org.broad.igv.ui.AboutDialog;

/**
 * Version helper for the self-update feature.
 *
 * <p>The running version comes from {@link Globals#VERSION} when the build stamped one, and
 * otherwise from the constant the About dialog shows.  {@link #compare(String, String)} is a
 * pure function so it can be unit tested (see {@code build/check/probe/VersionCompareProbe}).</p>
 */
public class AppVersion {

    private AppVersion() {
        // Utility class
    }

    /**
     * @return the version of the running application, never {@code null}
     */
    public static String current() {
        String version = Globals.VERSION;
        if (isKnown(version)) {
            return normalize(version);
        }
        // Same fallback the About dialog uses, so the two can never disagree.
        return normalize(AboutDialog.appVersion());
    }

    /** A build stamp is usable only when it is an actual version number. */
    private static boolean isKnown(String version) {
        return version != null && !version.isBlank()
                && !"user".equalsIgnoreCase(version.trim())
                && !version.contains("not_set")
                && !version.contains("???");
    }

    /**
     * Compare two dotted version strings.
     *
     * <p>Tolerates a leading {@code v}, missing trailing segments ({@code 1.0 == 1.0.0}),
     * non-numeric suffixes ({@code 1.0.3-beta}) and build metadata ({@code 1.0.3+build.7}).
     * A pre-release marker makes a version older than the corresponding release.</p>
     *
     * @param a first version
     * @param b second version
     * @return negative if {@code a < b}, zero if equal, positive if {@code a > b}
     */
    public static int compare(String a, String b) {
        long[] partsA = numericParts(a);
        long[] partsB = numericParts(b);
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            long left = i < partsA.length ? partsA[i] : 0;
            long right = i < partsB.length ? partsB[i] : 0;
            if (left != right) {
                return left < right ? -1 : 1;
            }
        }
        String labelA = preReleaseLabel(a);
        String labelB = preReleaseLabel(b);
        if (labelA == null && labelB == null) {
            return 0;
        }
        if (labelA == null) {
            return 1;    // release beats pre-release
        }
        if (labelB == null) {
            return -1;
        }
        return Integer.signum(comparePreRelease(labelA, labelB));
    }

    /**
     * Order two pre-release labels using semver rules: dot separated identifiers, numeric
     * identifiers compared as numbers (so {@code beta.2 < beta.10}), numeric identifiers ranking
     * below alphanumeric ones, and the shorter label ranking lower when all shared identifiers
     * are equal.
     */
    private static int comparePreRelease(String a, String b) {
        String[] idsA = a.split("\\.");
        String[] idsB = b.split("\\.");
        int length = Math.max(idsA.length, idsB.length);
        for (int i = 0; i < length; i++) {
            if (i >= idsA.length) {
                return -1;
            }
            if (i >= idsB.length) {
                return 1;
            }
            String idA = idsA[i];
            String idB = idsB[i];
            boolean numericA = isAllDigits(idA);
            boolean numericB = isAllDigits(idB);
            int cmp;
            if (numericA && numericB) {
                cmp = Long.compare(parseLongOrMax(idA), parseLongOrMax(idB));
            } else if (numericA != numericB) {
                cmp = numericA ? -1 : 1;
            } else {
                cmp = idA.compareToIgnoreCase(idB);
            }
            if (cmp != 0) {
                return Integer.signum(cmp);
            }
        }
        return 0;
    }

    /**
     * @return true when {@code candidate} is a strictly newer version than {@code current}
     */
    public static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }
        return compare(candidate, current) > 0;
    }

    /** Strip a leading {@code v}/{@code V} and surrounding blanks. */
    public static String normalize(String version) {
        if (version == null) {
            return "";
        }
        String v = version.trim();
        if (!v.isEmpty() && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) {
            v = v.substring(1).trim();
        }
        return v;
    }

    /** Dotted numeric segments; every leading run of digits of a segment is used. */
    static long[] numericParts(String version) {
        String v = stripMetadata(normalize(version));
        if (v.isEmpty()) {
            return new long[0];
        }
        // Only the part before a pre-release marker is numeric ("1.0.3-beta" -> "1.0.3").
        int dash = v.indexOf('-');
        if (dash >= 0) {
            v = v.substring(0, dash);
        }
        String[] segments = v.split("\\.");
        long[] parts = new long[segments.length];
        for (int i = 0; i < segments.length; i++) {
            parts[i] = leadingLong(segments[i]);
        }
        return parts;
    }

    private static String stripMetadata(String version) {
        int plus = version.indexOf('+');
        return plus >= 0 ? version.substring(0, plus) : version;
    }

    /**
     * Value of the leading digit run of a segment.  A segment without digits counts as zero;
     * a run too long for a {@code long} saturates at {@link Long#MAX_VALUE} so that an absurdly
     * large version still compares as "newer" instead of silently collapsing to 0.
     */
    private static long leadingLong(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        return parseLongOrMax(segment.substring(0, end));
    }

    private static long parseLongOrMax(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        String value = digits.substring(start);
        if (value.length() > 18) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean hasPreRelease(String version) {
        return preReleaseLabel(version) != null;
    }

    private static String preReleaseLabel(String version) {
        String v = stripMetadata(normalize(version));
        int dash = v.indexOf('-');
        return dash >= 0 ? v.substring(dash + 1) : null;
    }
}
