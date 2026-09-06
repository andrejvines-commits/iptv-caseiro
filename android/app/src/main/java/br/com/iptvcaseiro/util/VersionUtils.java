package br.com.iptvcaseiro.util;

public final class VersionUtils {
    private VersionUtils() {}

    public static boolean isNewer(String candidate, String current) {
        String[] left = candidate.replaceFirst("^[vV]", "").split("[^0-9]+");
        String[] right = current.replaceFirst("^[vV]", "").split("[^0-9]+");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length && !left[i].isEmpty() ? Integer.parseInt(left[i]) : 0;
            int b = i < right.length && !right[i].isEmpty() ? Integer.parseInt(right[i]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }
}
