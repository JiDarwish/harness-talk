package com.example.bookshelf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "features")
public class FeatureFlags {
    private boolean loanExtensionEnabled = false;

    public boolean isLoanExtensionEnabled() { return loanExtensionEnabled; }
    public void setLoanExtensionEnabled(boolean loanExtensionEnabled) {
        this.loanExtensionEnabled = loanExtensionEnabled;
    }
}
