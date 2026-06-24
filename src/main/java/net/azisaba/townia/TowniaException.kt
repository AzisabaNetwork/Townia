package net.azisaba.townia;

public class TowniaException extends Exception {

    private final String messageKey;
    private final String[] replacements;

    public TowniaException(String messageKey, String... replacements) {
        super(messageKey);
        this.messageKey = messageKey;
        this.replacements = replacements;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String[] getReplacements() {
        return replacements;
    }
}
