package de.intranda.goobi.plugins;

public enum ProcessProperties {
    PROCESSNAME("ProcessName"),
    FILENAME("FileName"),
    PUBLICATIONTYPE("PublicationType"),
    CATALOGIDDIGITAL("CatalogIDDigital");

    private ProcessProperties(String notation) {
        this.notation = notation;
    }

    private final String notation;

    @Override
    public String toString() {
        return this.notation;
    }
}
