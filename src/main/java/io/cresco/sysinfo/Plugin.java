package io.cresco.sysinfo;


import io.cresco.library.agent.AgentService;
import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.plugin.PluginService;
import io.cresco.library.utilities.CLogger;
import org.apache.felix.hc.api.HealthCheck;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.*;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

@Component(
        service = { PluginService.class },
        scope=ServiceScope.PROTOTYPE,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        reference=@Reference(name="io.cresco.library.agent.AgentService", service=AgentService.class)
)

public class Plugin implements PluginService {

    private BundleContext context;
    private PluginBuilder pluginBuilder;
    private Executor executor;
    private CLogger logger;
    private Map<String,Object> map;
    private PerfSysMonitor perfSysMonitor;

    private SysInfoBuilder builder;
    private Benchmark bmark;
    // Felix HealthCheck registration (central health); best-effort, unregistered on stop
    private ServiceRegistration<HealthCheck> healthReg;


    @Activate
    void activate(BundleContext context, Map<String,Object> map) {

        this.context = context;
        this.map = map;

    }

    @Modified
    void modified(BundleContext context, Map<String,Object> map) {
        logger.info("Modified Config Map PluginID:" + (String) map.get("pluginID"));
    }

    @Deactivate
    void deactivate(BundleContext context, Map<String,Object> map) {

        isStopped();
        this.context = null;
        this.map = null;

    }


    @Override
    public boolean isActive() {
        return pluginBuilder.isActive();
    }

    @Override
    public void setIsActive(boolean isActive) {
        pluginBuilder.setIsActive(isActive);
    }

    @Override
    public boolean inMsg(MsgEvent incoming) {
        pluginBuilder.msgIn(incoming);
        return true;
    }

    @Override
    public boolean isStarted() {

        try {
            if(pluginBuilder == null) {
                pluginBuilder = new PluginBuilder(this.getClass().getName(), context, map);
                this.logger = pluginBuilder.getLogger(Plugin.class.getName(), CLogger.Level.Info);

                builder = new SysInfoBuilder(pluginBuilder);
                bmark = new Benchmark(pluginBuilder);

                this.executor = new ExecutorImpl(pluginBuilder,builder,bmark);
                pluginBuilder.setExecutor(executor);

                while (!pluginBuilder.getAgentService().getAgentState().isActive()) {
                    logger.info("Plugin " + pluginBuilder.getPluginID() + " waiting on Agent Init");
                    Thread.sleep(1000);

                }

                //set plugin active
                pluginBuilder.setIsActive(true);

                // Register the central Felix HealthCheck (best-effort; must never break startup).
                registerHealthCheck();

                if (pluginBuilder.getConfig().getBooleanParam("enable_perf", false)) {
                    perfSysMonitor = new PerfSysMonitor(pluginBuilder, builder);
                    perfSysMonitor.start();
                    logger.info("Performance System monitoring initialized");
                }

            }
            return true;
        } catch(Exception ex) {
            if(logger != null) {
                logger.error("isStarted() " + ex.getMessage());
            }
            //ex.printStackTrace();
            return false;
        }
    }

    /** Register sysinfo's Felix HealthCheck so CrescoHealthExecutor discovers it. Best-effort. */
    private void registerHealthCheck() {
        try {
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(HealthCheck.NAME, "sysinfo");
            props.put(HealthCheck.TAGS, new String[]{"local"});
            healthReg = context.registerService(HealthCheck.class,
                    new SysInfoHealthCheck(pluginBuilder, builder), props);
            logger.info("Registered sysinfo HealthCheck (Felix HC)");
        } catch (Throwable t) {
            // health is best-effort: a missing hc.api bundle must never break sysinfo
            if (logger != null) logger.warn("Could not register sysinfo HealthCheck: " + t.getMessage());
        }
    }

    @Override
    public boolean isStopped() {
        try {
            if (healthReg != null) { healthReg.unregister(); healthReg = null; }
        } catch (Exception ignore) { }
        if(perfSysMonitor != null) {
            perfSysMonitor.stop();
        }
        if(pluginBuilder != null) {
            pluginBuilder.setExecutor(null);
            pluginBuilder.setIsActive(false);
        }
        return true;
    }


}