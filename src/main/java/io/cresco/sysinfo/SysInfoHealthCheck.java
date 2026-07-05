package io.cresco.sysinfo;

import io.cresco.library.plugin.PluginBuilder;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;

/**
 * Central health for sysinfo. Registered as an {@code org.apache.felix.hc.api.HealthCheck} OSGi
 * service (name "sysinfo", tag "local") so the controller's CrescoHealthExecutor discovers and
 * schedules it alongside the built-in checks — the same Felix Health Check system every other
 * Cresco bundle uses. sysinfo's job is always-on host telemetry, so an active plugin whose
 * MeasurementEngine serves metrics is healthy; self-guards while the plugin is still coming up.
 */
public class SysInfoHealthCheck implements HealthCheck {

    private final PluginBuilder plugin;
    private final SysInfoBuilder builder;

    public SysInfoHealthCheck(PluginBuilder plugin, SysInfoBuilder builder) {
        this.plugin = plugin;
        this.builder = builder;
    }

    @Override
    public Result execute() {
        try {
            if (plugin == null || !plugin.isActive() || builder == null) {
                return new Result(Result.Status.TEMPORARILY_UNAVAILABLE, "sysinfo not active");
            }
            return new Result(Result.Status.OK, "sysinfo OK: host telemetry available");
        } catch (Exception ex) {
            return new Result(Result.Status.WARN, "sysinfo health error: " + ex.getMessage());
        }
    }
}
