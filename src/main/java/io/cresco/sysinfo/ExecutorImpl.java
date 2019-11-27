package io.cresco.sysinfo;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

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