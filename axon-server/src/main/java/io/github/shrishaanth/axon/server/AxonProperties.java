package io.github.shrishaanth.axon.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits and settings, all overridable by environment variables (see application.yml). */
@ConfigurationProperties(prefix = "axon")
public class AxonProperties {

    private int maxSpecBytes = 8 * 1024 * 1024;
    private int maxIngestBytes = 2 * 1024 * 1024;
    private int maxEventsPerRequest = 1000;
    private long maxEventsPerWorkspace = 200_000;
    private int maxWorkspaces = 200;
    private int retentionDays = 90;
    private double ingestRequestsPerSecond = 20;
    private String corsOrigins = "http://localhost:5173";

    public int getMaxSpecBytes() {
        return maxSpecBytes;
    }

    public void setMaxSpecBytes(int maxSpecBytes) {
        this.maxSpecBytes = maxSpecBytes;
    }

    public int getMaxIngestBytes() {
        return maxIngestBytes;
    }

    public void setMaxIngestBytes(int maxIngestBytes) {
        this.maxIngestBytes = maxIngestBytes;
    }

    public int getMaxEventsPerRequest() {
        return maxEventsPerRequest;
    }

    public void setMaxEventsPerRequest(int maxEventsPerRequest) {
        this.maxEventsPerRequest = maxEventsPerRequest;
    }

    public long getMaxEventsPerWorkspace() {
        return maxEventsPerWorkspace;
    }

    public void setMaxEventsPerWorkspace(long maxEventsPerWorkspace) {
        this.maxEventsPerWorkspace = maxEventsPerWorkspace;
    }

    public int getMaxWorkspaces() {
        return maxWorkspaces;
    }

    public void setMaxWorkspaces(int maxWorkspaces) {
        this.maxWorkspaces = maxWorkspaces;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public double getIngestRequestsPerSecond() {
        return ingestRequestsPerSecond;
    }

    public void setIngestRequestsPerSecond(double ingestRequestsPerSecond) {
        this.ingestRequestsPerSecond = ingestRequestsPerSecond;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }
}
