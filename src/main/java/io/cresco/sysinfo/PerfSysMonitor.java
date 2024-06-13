package io.cresco.sysinfo;

import jakarta.jms.*;

import io.cresco.library.data.TopicType;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.util.Timer;
import java.util.TimerTask;

public class PerfSysMonitor {
    private SysInfoBuilder builder;
    private Benchmark bmark;
    private BenchMetric bm;
    private Timer timer;
    private boolean running = false;

    private PluginBuilder plugin;
    private CLogger logger;


    public PerfSysMonitor(PluginBuilder plugin, SysInfoBuilder builder) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(PerfSysMonitor.class.getName(),CLogger.Level.Info);
        this.builder = builder;


        if(plugin.getConfig().getBooleanParam("benchmark",false)) {
            bmark = new Benchmark(plugin);
            bm = bmark.bench();
        }

    }

    public PerfSysMonitor start() {
        if (this.running) return this;
        Long interval = plugin.getConfig().getLongParam("perftimer", 10000L);

        timer = new Timer();
        timer.scheduleAtFixedRate(new PerfMonitorTask(plugin), 500L, interval);
        return this;

    }

    public PerfSysMonitor restart() {
        if (running) timer.cancel();
        running = false;
        return start();
    }

    public void stop() {
        timer.cancel();
        running = false;

    }

    private class PerfMonitorTask extends TimerTask {
        private PluginBuilder plugin;

        PerfMonitorTask(PluginBuilder plugin) {
            this.plugin = plugin;
        }


        public void run() {

            try {
                MapMessage mapMessage = plugin.getAgentService().getDataPlaneService().createMapMessage();

                if (plugin.getConfig().getBooleanParam("benchmark", false)) {
                    mapMessage.setDouble("benchmark_cpu_composite", bm.getCPU());
                }

                mapMessage.setString("perf",builder.getSysInfoMap());

                //set property
                mapMessage.setStringProperty("pluginname",plugin.getConfig().getStringParam("pluginname"));
                mapMessage.setStringProperty("region_id",plugin.getRegion());
                mapMessage.setStringProperty("agent_id",plugin.getAgent());
                mapMessage.setStringProperty("plugin_id", plugin.getPluginID());

                plugin.getAgentService().getDataPlaneService().sendMessage(TopicType.AGENT,mapMessage);


            } catch (Exception ex) {
                logger.error(ex.getMessage());
                //ex.printStackTrace();
            }

        }
    }
}
