package com.tyrak.box.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sync")
public class SyncProperties {
    /**
     * Carpeta local del PC que se va a vigilar automáticamente.
     */
    private String sourceLocation = "";

    /**
     * Si la sincronización automática está activa.
     */
    private boolean enabled = true;

    /**
     * Usuario dueño de la sincronización local.
     */
    private String username = "sync";

    public String getSourceLocation() {
        return sourceLocation;
    }

    public void setSourceLocation(String sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
