package de.intranda.goobi.plugins;

public enum ProcessProperties {
    PROCESSNAME("ProcessName"),
    FILENAME("FileName");
    
    private ProcessProperties(String notation) {
        this.notation = notation;
    }

    final private String notation;

    @Override
    public String toString() {
        return notation;
    }
}
