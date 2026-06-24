package net.azisaba.townia.data;

public class PermissionMatrix {
    public static final String FULL_PERMS = "BDSI";
    public static final String NO_PERMS = "";

    public static boolean hasPerm(String permString, char type) {
        if (permString == null) return false;
        return permString.indexOf(type) >= 0;
    }

    public static String setPerm(String permString, char type, boolean value) {
        if (permString == null) permString = "";
        boolean has = hasPerm(permString, type);
        if (value && !has) {
            return permString + type;
        } else if (!value && has) {
            return permString.replace(String.valueOf(type), "");
        }
        return permString;
    }
}
