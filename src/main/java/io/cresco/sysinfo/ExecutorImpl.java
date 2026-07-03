package io.cresco.sysinfo;

import io.cresco.library.capability.*;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

@CrescoCapabilities(namespace = "sysinfo", target = "plugin",
        routingParams = {"region", "agent", "pluginid"},
        summary = "Host system-information plugin: OS/CPU/memory/disk/network snapshot, CPU benchmark, and unified live metrics (incl. sensors/power/gpu).")
@CrescoActions({
    @CrescoAction(name = "getsysinfo",
        summary = "Return a full host system-information snapshot (OS, CPU, memory, disk, filesystem, network) as compressed JSON.",
        why = "Use for a one-shot host inventory / resource view. For time-series metrics prefer getmetrics.",
        returns = @CrescoReturn(name = "perf", type = "object", compressed = true, description = "compressed JSON: {os,cpu,mem,disk,fs,net,...}")),
    @CrescoAction(name = "getbenchmark",
        summary = "Run and return a SciMark2 CPU benchmark result as compressed JSON.",
        why = "Use to gauge relative CPU compute capacity of a host before placing compute-heavy plugins.",
        returns = @CrescoReturn(name = "bench", type = "object", compressed = true, description = "compressed JSON benchmark scores")),
    @CrescoAction(name = "getmetrics",
        summary = "Return this plugin's live metrics (cpu/memory/disk/network/sensors/power/gpu/devices) as MeasurementEngine gauges JSON.",
        why = "Standard cross-bundle metrics contract; the controller folds this into getmetricinventory.",
        returns = @CrescoReturn(name = "metrics", type = "object", description = "getAllMetrics() JSON grouped by metric group")),
    @CrescoAction(name = "getcapabilities",
        summary = "Return this plugin's self-describing capability document (its message actions as LLM tool specs).",
        why = "Discovery: lets a client/LLM learn what this plugin can do and how to call it.",
        returns = @CrescoReturn(name = "capabilities", type = "object", description = "CapabilityDocument JSON"))
})
public class ExecutorImpl implements Executor {

    private PluginBuilder plugin;
    private CLogger logger;
    private SysInfoBuilder builder;
    private Benchmark bmark;

    public ExecutorImpl(PluginBuilder pluginBuilder, SysInfoBuilder builder, Benchmark bmark) {
        this.plugin = pluginBuilder;
        this.logger = plugin.getLogger(ExecutorImpl.class.getName(),CLogger.Level.Info);
        this.builder = builder;
        this.bmark = bmark;
    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeDISCOVER(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeERROR(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeINFO(MsgEvent incoming) { return null; }
    @Override
    public MsgEvent executeEXEC(MsgEvent incoming) {

        logger.debug("Processing Exec message : " + incoming.getParams());

        if(incoming.getParams().containsKey("action")) {
            switch (incoming.getParam("action")) {

                case "getsysinfo":
                    incoming.setCompressedParam("perf",builder.getSysInfoMap());
                    return incoming;
                case "getbenchmark":
                    incoming.setCompressedParam("bench",bmark.getJSON());
                    return incoming;
                case "getmetrics":
                    // B-2 unified metrics: cpu/mem/disk/network as MeasurementEngine gauges
                    incoming.setParam("metrics", builder.getMetricsJson());
                    incoming.setParam("status", "10");
                    return incoming;
                case "getcapabilities":
                    return CapabilityResponder.respond(incoming, this);
            }
        }
        return null;
    }
    @Override
    public MsgEvent executeWATCHDOG(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeKPI(MsgEvent incoming) {
        return null;
    }


}