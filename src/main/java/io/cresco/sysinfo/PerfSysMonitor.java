package io.cresco.sysinfo;


import io.cresco.library.messaging.MsgEvent;
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


    public PerfSysMonitor(PluginBuilder plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger(PerfSysMonitor.class.getName(),CLogger.Level.Info);

        builder = new SysInfoBuilder();

        if(plugin.getConfig().getBooleanParam("benchmark",false)) {
            bmark = new Benchmark();
            bm = bmark.bench();
        }

    }

    public PerfSysMonitor start() {
        if (this.running) return this;
        Long interval = plugin.getConfig().getLongParam("perftimer", 5000L);

        /*
        MsgEvent initial = new MsgEvent(MsgEvent.Type.INFO, plugin.getRegion(), plugin.getAgent(), plugin.getPluginID(), "Performance Monitoring timer set to " + interval + " milliseconds.");
        initial.setParam("src_region", plugin.getRegion());
        initial.setParam("src_agent", plugin.getAgent());
        initial.setParam("src_plugin", plugin.getPluginID());
        initial.setParam("dst_region", plugin.getRegion());
        initial.setParam("dst_agent", plugin.getAgent());
        initial.setParam("dst_plugin", plugin.getPluginID());
        initial.setParam("is_regional",Boolean.TRUE.toString());
        initial.setParam("is_global",Boolean.TRUE.toString());
        plugin.sendMsgEvent(initial);
        */

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

            MsgEvent tick = plugin.getKPIMsgEvent();
            /*
            MsgEvent tick = new MsgEvent(MsgEvent.Type.KPI, plugin.getRegion(), plugin.getAgent(), plugin.getPluginID(), "Performance Monitoring tick.");
            tick.setParam("src_region", plugin.getRegion());
            tick.setParam("src_agent", plugin.getAgent());
            tick.setParam("src_plugin", plugin.getPluginID());
            tick.setParam("dst_region", plugin.getRegion());
            tick.setParam("dst_agent", plugin.getAgent());
            tick.setParam("dst_plugin", "plugin/0");
            tick.setParam("is_regional",Boolean.TRUE.toString());
            tick.setParam("is_global",Boolean.TRUE.toString());
            */


            tick.setParam("resource_id",plugin.getConfig().getStringParam("resource_id","sysinfo_resource"));
            tick.setParam("inode_id",plugin.getConfig().getStringParam("inode_id","sysinfo_inode"));

            if(plugin.getConfig().getBooleanParam("benchmark",false)) {
                tick.setParam("benchmark_cpu_composite",String.valueOf((int)bm.getCPU()));
            }

            tick.setCompressedParam("perf",builder.getSysInfoMap());
            /*
            for(Map.Entry<String, String> entry : builder.getSysInfoMap().entrySet()) {
                tick.setParam(entry.getKey(), entry.getValue());
            }
            */

            plugin.msgOut(tick);
        }
    }
}
