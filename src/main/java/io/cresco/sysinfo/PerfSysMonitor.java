package io.cresco.sysinfo;

import jakarta.jms.*;

import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Continuous system-info sampler: on a fixed interval it publishes a MapMessage of live
 * OS/CPU/memory/disk/network metrics to the agent dataplane.
 *
 * <p>This is a <em>measurement</em> path only. The SciMark2 CPU benchmark is deliberately NOT run
 * here — running a multi-second CPU burn at startup (and bundling its cached score into every
 * heartbeat) conflated an on-demand benchmark with continuous telemetry. The benchmark is now an
 * on-demand operation exposed via the plugin's {@code getbenchmark} action, so it only runs when
 * explicitly requested and never taxes normal operation.
 *
 * <p>{@link #getLastSampleTs()} / {@link #isRunning()} back the plugin's health check (sampler
 * liveness/freshness).
 */
public class PerfSysMonitor {
    private final SysInfoBuilder builder;
    private Timer timer;
    private volatile boolean running = false;
    private volatile long lastSampleTs = 0L;
    private volatile long intervalMs = 10000L;

    private final PluginBuilder plugin;
    private final CLogger logger;

    public PerfSysMonitor(PluginBuilder plugin, SysInfoBuilder builder) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(PerfSysMonitor.class.getName(), CLogger.Level.Info);
        this.builder = builder;
    }

    public PerfSysMonitor start() {
        if (this.running) return this;
        this.intervalMs = plugin.getConfig().getLongParam("perftimer", 10000L);
        timer = new Timer("sysinfo-perf", true);
        timer.scheduleAtFixedRate(new PerfMonitorTask(plugin), 500L, intervalMs);
        this.running = true;
        return this;
    }

    public PerfSysMonitor restart() {
        if (running && timer != null) timer.cancel();
        running = false;
        return start();
    }

    public void stop() {
        if (timer != null) timer.cancel();
        running = false;
    }

    /** True once the sampling timer is scheduled. */
    public boolean isRunning() { return running; }

    /** Wall-clock time of the last successfully published sample (0 until the first sample). */
    public long getLastSampleTs() { return lastSampleTs; }

    /** Configured sampling interval in milliseconds. */
    public long getIntervalMs() { return intervalMs; }

    private class PerfMonitorTask extends TimerTask {
        private final PluginBuilder plugin;

        PerfMonitorTask(PluginBuilder plugin) {
            this.plugin = plugin;
        }

        public void run() {
            try {
                MapMessage mapMessage = plugin.getAgentService().getDataPlaneService().createMapMessage();
                mapMessage.setString("perf", builder.getSysInfoMap());

                mapMessage.setStringProperty("pluginname", plugin.getConfig().getStringParam("pluginname"));
                mapMessage.setStringProperty("region_id", plugin.getRegion());
                mapMessage.setStringProperty("agent_id", plugin.getAgent());
                mapMessage.setStringProperty("plugin_id", plugin.getPluginID());

                plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT, mapMessage);
                lastSampleTs = System.currentTimeMillis();
            } catch (Exception ex) {
                logger.error(ex.getMessage());
            }
        }
    }
}
