package io.cresco.sysinfo;

import com.google.gson.Gson;
import io.cresco.library.metrics.CMetric;
import io.cresco.library.metrics.MeasurementEngine;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import oshi.SystemInfo;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.*;

class SysInfoBuilder {
    private SystemInfo systemInfo;
    private HardwareAbstractionLayer hardwareAbstractionLayer;
    private OperatingSystem os;
    private Gson gson;
    private CLogger logger;

    // B-2 metrics unification: expose the same OSHI telemetry as getsysinfo, but as MeasurementEngine
    // gauges so sysinfo participates in the unified getmetrics/getmetricinventory view. getsysinfo stays
    // for back-compat. prevTicks lets us report CPU load since the last read without blocking.
    private final PluginBuilder plugin;
    private MeasurementEngine metricEngine;
    private long[] prevTicks;
    // OSHI NIC/filesystem enumeration costs ~2s+ EACH call, which would blow the metric-inventory RPC
    // budget. Cache the serialized result for a short TTL so repeated getmetrics reads are instant.
    private volatile String cachedMetricsJson;
    private volatile long cachedMetricsAt;

    public SysInfoBuilder(PluginBuilder plugin) {
        gson = new Gson();
        this.plugin = plugin;

        this.logger = plugin.getLogger(SysInfoBuilder.class.getName(),CLogger.Level.Info);
        // Pre-warm the metrics cache off the startup path so the first getmetrics RPC hits a warm cache.
        Thread warm = new Thread(() -> { try { getMetricsJson(); } catch (Throwable ignore) {} }, "sysinfo-metrics-prewarm");
        warm.setDaemon(true);
        warm.start();

        /*
        System.setProperty("java.net.preferIPv4Stack", "true");
        try {

            } catch (Exception e) {
                System.out.println("SysInfoBuilder : Constructor : nicLoop : Error : " + e.getMessage());
            }
            getSysInfoMap();
        } catch (Exception e) {
            System.out.println("SysInfoBuilder : Constructor : Error : " + e.getMessage());
            e.printStackTrace();
        }
        */
    }

    public String getSysInfoFullMap() {
        String jsonInfo = null;
        try {
            systemInfo = new SystemInfo();
            hardwareAbstractionLayer = systemInfo.getHardware();
            os = systemInfo.getOperatingSystem();

            Map<String,List<Map<String,String>>> info = new HashMap<>();
            info.put("os",getOSInfo());
            info.put("cpu",getCPUInfo());
            info.put("mem",getMemoryInfo());
            info.put("disk",getDiskInfo());
            info.put("fs",getFSInfo());
            info.put("part",getPartitionInfo());
            info.put("net",getNetworkInfo());
            info.put("proc",getProcessInfo());

            //getSensorInfo();
            jsonInfo = gson.toJson(info);

        } catch (Exception ex) {
            logger.error("SysInfoBuilder : getSysInfoMap : Error : " + ex.getMessage(), ex);
        }
        return jsonInfo;
    }

    public String getSysInfoMap() {
        String jsonInfo = null;
        try {

            if(systemInfo == null) {
                systemInfo = new SystemInfo();
            }

            if(hardwareAbstractionLayer == null) {
                hardwareAbstractionLayer = systemInfo.getHardware();
            }

            if(os == null) {
                os = systemInfo.getOperatingSystem();
            }

            Map<String,List<Map<String,String>>> info = new HashMap<>();
            info.put("os",getOSInfo());
            info.put("cpu",getCPUInfo());
            info.put("mem",getMemoryInfo());
            info.put("disk",getDiskInfo());

            //todo Raspbian GNU/Linux 9 (stretch) build 4.14.34-v7+ (32-bit)
            // and perhaps others hang on os.getFileSystem().getFileStores()
            if(!os.getFamily().contains("Raspbian")) {
                info.put("fs",getFSInfo());
            } else {
                logger.trace("Disabling FSInfo for Raspbian-based systems.");
            }
            info.put("part",getPartitionInfo());
            info.put("net",getNetworkInfo());
            //info.put("proc",getProcessInfo());

            //getSensorInfo();
            jsonInfo = gson.toJson(info);
            info.clear();

        } catch (Exception e) {
            logger.error("SysInfoBuilder : getSysInfoMap : Error : " + e.getMessage(), e);
        }
        return jsonInfo;
    }

    /**
     * B-2 unified metrics: the same CPU/memory/disk/network telemetry as getsysinfo, exposed as
     * MeasurementEngine gauges and serialized via the standard getAllMetrics() shape so sysinfo folds
     * into the fabric-wide getmetricinventory. Non-blocking: CPU load is computed from tick deltas
     * across calls. Returns {"processor":[...],"memory":[...],"disk":[...],"network":[...]}.
     */
    public String getMetricsJson() {
        long ttl = plugin.getConfig().getIntegerParam("sysinfo_metrics_ttl_ms", 5000);
        String cached = cachedMetricsJson;
        if (cached != null && (System.currentTimeMillis() - cachedMetricsAt) < ttl) {
            return cached;
        }
        return refreshMetricsJson();
    }

    private synchronized String refreshMetricsJson() {
        long ttl = plugin.getConfig().getIntegerParam("sysinfo_metrics_ttl_ms", 5000);
        if (cachedMetricsJson != null && (System.currentTimeMillis() - cachedMetricsAt) < ttl) {
            return cachedMetricsJson; // another thread just refreshed
        }
        try {
            // Register the gauges FIRST — before any OSHI/JNA access. OSHI hardware calls go through a
            // native lib that can fail to load (e.g. arch-mismatched JNA) and raise an Error; if that
            // happened before registration, getAllMetrics() would return nothing and sysinfo would vanish
            // from the unified inventory. Registering first guarantees the processor/memory/disk/network
            // groups always appear (real values where OSHI works, zeros where the platform can't provide).
            if (metricEngine == null) {
                metricEngine = new MeasurementEngine(plugin);
                metricEngine.setGauge("system.cpu.count", "logical CPU count", "processor", CMetric.MeasureClass.GAUGE_INT);
                metricEngine.setGauge("system.cpu.load", "system CPU load since last read (0..1)", "processor", CMetric.MeasureClass.GAUGE_DOUBLE);
                metricEngine.setGauge("memory.total", "total physical memory (bytes)", "memory", CMetric.MeasureClass.GAUGE_LONG);
                metricEngine.setGauge("memory.available", "available physical memory (bytes)", "memory", CMetric.MeasureClass.GAUGE_LONG);
                metricEngine.setGauge("disk.total", "total filesystem space (bytes)", "disk", CMetric.MeasureClass.GAUGE_LONG);
                metricEngine.setGauge("disk.available", "usable filesystem space (bytes)", "disk", CMetric.MeasureClass.GAUGE_LONG);
                metricEngine.setGauge("net.bytes.sent", "network bytes sent (all interfaces)", "network", CMetric.MeasureClass.GAUGE_LONG);
                metricEngine.setGauge("net.bytes.recv", "network bytes received (all interfaces)", "network", CMetric.MeasureClass.GAUGE_LONG);
            }

            // Best-effort OSHI reads. EVERY hardware access is individually guarded with Throwable —
            // even obtaining the processor/memory handles can raise NoClassDefFoundError/UnsatisfiedLinkError
            // when OSHI's JNA native lib can't load (e.g. arch-mismatched). Whatever fails simply leaves its
            // gauge at 0; the registered gauges are ALWAYS emitted below, so sysinfo never vanishes from the
            // unified inventory (real values where OSHI works, zeros where the platform can't provide).
            try { if (systemInfo == null) { systemInfo = new SystemInfo(); } } catch (Throwable ignore) {}
            try { if (hardwareAbstractionLayer == null && systemInfo != null) { hardwareAbstractionLayer = systemInfo.getHardware(); } } catch (Throwable ignore) {}
            try { if (os == null && systemInfo != null) { os = systemInfo.getOperatingSystem(); } } catch (Throwable ignore) {}

            try {
                var cpu = hardwareAbstractionLayer.getProcessor();
                try { metricEngine.updateIntGauge("system.cpu.count", cpu.getLogicalProcessorCount()); } catch (Throwable ignore) {}
                try {
                    long[] ticks = cpu.getSystemCpuLoadTicks();
                    double load = (prevTicks != null) ? cpu.getSystemCpuLoadBetweenTicks(prevTicks) : 0.0d;
                    prevTicks = ticks;
                    metricEngine.updateDoubleGauge("system.cpu.load", Double.isNaN(load) ? 0.0d : load);
                } catch (Throwable ignore) { /* native cpu-load unavailable */ }
            } catch (Throwable ignore) { /* no processor handle on this platform */ }

            try {
                metricEngine.updateLongGauge("memory.total", hardwareAbstractionLayer.getMemory().getTotal());
                metricEngine.updateLongGauge("memory.available", hardwareAbstractionLayer.getMemory().getAvailable());
            } catch (Throwable ignore) {}

            try {
                long diskTotal = 0L, diskAvail = 0L;
                for (OSFileStore fs : os.getFileSystem().getFileStores()) {
                    diskTotal += fs.getTotalSpace();
                    diskAvail += fs.getUsableSpace();
                }
                metricEngine.updateLongGauge("disk.total", diskTotal);
                metricEngine.updateLongGauge("disk.available", diskAvail);
            } catch (Throwable ignore) { /* FS enumeration unavailable; skip */ }

            try {
                long netSent = 0L, netRecv = 0L;
                for (NetworkIF net : hardwareAbstractionLayer.getNetworkIFs()) {
                    netSent += net.getBytesSent();
                    netRecv += net.getBytesRecv();
                }
                metricEngine.updateLongGauge("net.bytes.sent", netSent);
                metricEngine.updateLongGauge("net.bytes.recv", netRecv);
            } catch (Throwable ignore) {}

            // ALWAYS emit the registered gauges (0 where OSHI couldn't populate them).
            String json = gson.toJson(metricEngine.getAllMetrics());
            cachedMetricsJson = json;
            cachedMetricsAt = System.currentTimeMillis();
            return json;
        } catch (Throwable ex) {
            logger.error("getMetricsJson() " + ex.getMessage(), ex);
            return cachedMetricsJson != null ? cachedMetricsJson : "{}";
        }
    }

    private List<Map<String,String>> getProcessInfo() {
        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();

            OSProcess[] p = os.getProcesses(0, OperatingSystem.ProcessSort.CPU);
            for(OSProcess op : p) {
                Map<String,String> info = new HashMap<>();
                info.put("name",op.getName());
                //info.put("commandline",op.getCommandLine());
                info.put("path",op.getPath());
                info.put("bytes-read",String.valueOf(op.getBytesRead()));
                info.put("bytes-written",String.valueOf(op.getBytesWritten()));
                info.put("kernel-time",String.valueOf(op.getKernelTime()));
                info.put("virtual-size",String.valueOf(op.getVirtualSize()));
                info.put("thread-count",String.valueOf(op.getThreadCount()));
                list.add(info);
            }
        }
        catch(Exception ex) {
            logger.error("getProcessInfo() " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<Map<String,String>> getFSInfo() {
        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();

            //OSFileStore[] fsArray = hardwareAbstractionLayer.getFileSystem().getFileStores();

            OSFileStore[] fsArray = os.getFileSystem().getFileStores();

            for (OSFileStore fs : fsArray) {
                Map<String,String> info = new HashMap<>();
                info.put("name",fs.getName());
                info.put("description",fs.getDescription());
                info.put("mount",fs.getMount());
                info.put("type",fs.getType());
                info.put("uuid",fs.getUUID());
                info.put("volume",fs.getVolume());
                info.put("total-space",String.valueOf(fs.getTotalSpace()));
                info.put("available-space",String.valueOf(fs.getUsableSpace()));
                list.add(info);
            }
        }
        catch(Exception ex) {
            logger.error("getFSInfo: " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<Map<String,String>> getOSInfo() {
        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();
            Map<String,String> info = new HashMap<>();
            try {
                info.put("sys-os", os.getVersion().toString());
            } catch (Exception e) {
                info.put("sys-os", "unknown");
            }

            try {
                info.put("sys-family", os.getFamily());
            } catch (Exception e) {
                info.put("sys-family", "unknown");
            }
            //info.put("sys-threadcount", String.valueOf(os.getThreadCount()));
            try {
                info.put("sys-manufacturer", os.getManufacturer());
            } catch (Exception e) {
                info.put("sys-manufacturer", "unknown");
            }
            //info.put("sys-uptime", FormatUtil.formatElapsedSecs(hardwareAbstractionLayer.getProcessor().getSystemUptime()));
            try {
                info.put("sys-uptime", String.valueOf(os.getSystemUptime()));
            } catch (Exception e) {
                info.put("sys-uptime", "unknown");
            }
            try {
                info.put("process-count", String.valueOf(os.getProcessCount()));
            } catch (Exception e) {
                info.put("process-count", "unknown");
            }
            list.add(info);
        }
        catch(Exception ex) {
            logger.error("getOSInfo() " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<Map<String,String>> getMemoryInfo() {
        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();
            Map<String,String> info = new HashMap<>();
            info.put("memory-total", String.valueOf(hardwareAbstractionLayer.getMemory().getTotal()));
            info.put("memory-available", String.valueOf(hardwareAbstractionLayer.getMemory().getAvailable()));
            info.put("swap-total", String.valueOf(hardwareAbstractionLayer.getMemory().getVirtualMemory().getSwapTotal()));
            info.put("swap-used", String.valueOf(hardwareAbstractionLayer.getMemory().getVirtualMemory().getSwapUsed()));
            list.add(info);

        }
        catch(Exception ex) {
            logger.error("getMemoryInfo() " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<Map<String,String>> getPartitionInfo() {
        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();

            HWDiskStore[] diskArray = hardwareAbstractionLayer.getDiskStores();
            for (HWDiskStore disk : diskArray) {

                for(HWPartition part : disk.getPartitions()) {
                    Map<String,String> info = new HashMap<>();
                    info.put("part-id", String.valueOf(part.getIdentification()));
                    info.put("part-name", String.valueOf(part.getName()));
                    info.put("part-size", String.valueOf(part.getSize()));
                    info.put("part-mount", String.valueOf(part.getMountPoint()));
                    list.add(info);
                }
            }

        }
        catch(Exception ex) {
            logger.error("getPartitionInfo() " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<Map<String,String>> getDiskInfo() {

        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();
            HWDiskStore[] diskArray = hardwareAbstractionLayer.getDiskStores();

            for (HWDiskStore disk : diskArray) {
                Map<String, String> info = new HashMap<>();

                info.put("disk-size", String.valueOf(disk.getSize()));
                info.put("disk-model", String.valueOf(disk.getModel()));
                info.put("disk-name", String.valueOf(disk.getName()));
                info.put("disk-readbytes", String.valueOf(disk.getReadBytes()));
                info.put("disk-reads", String.valueOf(disk.getReads()));
                info.put("disk-writebytes", String.valueOf(disk.getWriteBytes()));
                info.put("disk-writes", String.valueOf(disk.getWrites()));
                info.put("disk-transfertime", String.valueOf(disk.getTransferTime()));

                list.add(info);
            }
        }
        catch(Exception ex) {
            logger.error("getDiskInfo() " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<Map<String,String>> getNetworkInfo() {
        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();

            NetworkIF[] networks = hardwareAbstractionLayer.getNetworkIFs();
            for (NetworkIF net : networks) {
                Map<String,String> info = new HashMap<>();

                info.put("ipv4-addresses", gson.toJson(net.getIPv4addr()));
                info.put("ipv6-addresses", gson.toJson(net.getIPv6addr()));
                info.put("bytes-received", String.valueOf(net.getBytesRecv()));
                info.put("bytes-sent", String.valueOf(net.getBytesSent()));
                info.put("packets-received", String.valueOf(net.getPacketsRecv()));
                info.put("packets-sent", String.valueOf(net.getPacketsSent()));
                info.put("mtu",String.valueOf(net.getMTU()));
                info.put("interface-name",net.getName());
                info.put("mac",net.getMacaddr());
                info.put("errors-in",String.valueOf(net.getInErrors()));
                info.put("errors-out",String.valueOf(net.getOutErrors()));
                info.put("link-speed", String.valueOf(net.getSpeed()));
                info.put("timestamp",String.valueOf(net.getTimeStamp()));
                list.add(info);
            }
        }
        catch(Exception ex) {
            logger.error("getNetworkInfo() " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<Map<String,String>> getCPUInfo() {
        List<Map<String,String>> list = null;
        try{
            list = new ArrayList<>();
            Map<String,String> info = new HashMap<>();

            try {
                info.put("is64bit", String.valueOf(hardwareAbstractionLayer.getProcessor().isCpu64bit()));
            }
            catch (Exception ex) {
                info.put("is64bit", "unknown");
            }
            try {
                info.put("cpu-physical-count", String.valueOf(hardwareAbstractionLayer.getProcessor().getPhysicalProcessorCount()));
            }
            catch (Exception ex) {
                info.put("cpu-physical-count", "1");
            }
            try {
                info.put("cpu-logical-count", String.valueOf(hardwareAbstractionLayer.getProcessor().getLogicalProcessorCount()));
            }
            catch (Exception ex){
                info.put("cpu-logical-count", "1");
            }
            try {
                info.put("cpu-summary", hardwareAbstractionLayer.getProcessor().toString());
            }
            catch (Exception ex){
                info.put("cpu-summary", "unknown");
            }
            try {
                info.put("cpu-ident", hardwareAbstractionLayer.getProcessor().getIdentifier());
            }
            catch (Exception ex) {
                info.put("cpu-ident", "unknown");
            }
            try {
                info.put("cpu-id",hardwareAbstractionLayer.getProcessor().getProcessorID());
            }
            catch(Exception ex) {
                info.put("cpu-id","unknown");
            }

            try {
                //performance
                long[] prevTicks = hardwareAbstractionLayer.getProcessor().getSystemCpuLoadTicks();
                Thread.sleep(1000);
                long[] ticks = hardwareAbstractionLayer.getProcessor().getSystemCpuLoadTicks();

                long user = ticks[0] - prevTicks[0];
                long nice = ticks[1] - prevTicks[1];
                long sys = ticks[2] - prevTicks[2];
                long idle = ticks[3] - prevTicks[3];
                long totalCpu = user + nice + sys + idle;

                info.put("cpu-user-load", String.format("%.1f", (100d * user / totalCpu)));
                info.put("cpu-nice-load", String.format("%.1f", (100d * nice / totalCpu)));
                info.put("cpu-sys-load", String.format("%.1f", (100d * sys / totalCpu)));
                info.put("cpu-idle-load", String.format("%.1f", (100d * idle / totalCpu)));
            } catch (Exception e) {
                //known bug in Windows will throw exception
                info.put("cpu-user-load", "unknown");
                info.put("cpu-nice-load", "unknown");
                info.put("cpu-sys-load", "unknown");
                info.put("cpu-idle-load", "unknown");
            }

            list.add(info);

        }
        catch(Exception ex) {
            logger.error("getCPUInfo() " + ex.getMessage(), ex);
        }
        return list;
    }


/*
    private Map<String,String> getSensorInfo() {
        Map<String,String> info = null;
        try{

            info = new HashMap<>();
            info.put("sys-temperature", String.format("%.1f", hardwareAbstractionLayer.getSensors().getCpuTemperature()));
            info.put("sys-voltage", String.format("%.1f",hardwareAbstractionLayer.getSensors().getCpuVoltage()));
            StringBuilder fanStringBuilder = new StringBuilder();
            for(int fanspeed : hardwareAbstractionLayer.getSensors().getFanSpeeds()) {
                fanStringBuilder.append(String.valueOf(fanspeed)).append(",");
            }

            int index = fanStringBuilder.lastIndexOf(",");
            if(index != -1) {
                fanStringBuilder.deleteCharAt(index);
            }

            info.put("sys-fanspeeds", fanStringBuilder.toString());

        }
        catch(Exception ex) {
            ex.printStackTrace();
        }
        return info;
    }
*/


    private Map<String,String> getNetworkInterfaces() {
        Map<String,String> info = null;
        try{
            info = new HashMap<>();
            int nicCount = 0;
            StringBuilder nicStringBuilder = new StringBuilder();
            //try {

            Enumeration<NetworkInterface> nicEnum = NetworkInterface.getNetworkInterfaces();

            while (nicEnum.hasMoreElements()) {
                NetworkInterface nic = nicEnum.nextElement();

                if (nic.isLoopback())
                    continue;
                nicStringBuilder.append(String.valueOf(nicCount)).append(":").append(nic.getName()).append(",");
                StringBuilder interfaceAddressStringBuilder = new StringBuilder();

                for (InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
                    if (interfaceAddress == null)
                        continue;
                    try {
                        InetAddress address = interfaceAddress.getAddress();
                        interfaceAddressStringBuilder.append(address.getHostAddress()).append(",");
                    } catch (Exception e) {
                        logger.error("SysInfoBuilder : Constructor : nicLoop : addrLoop : Error : " + e.getMessage());
                    }
                }
                if (interfaceAddressStringBuilder.length() == 0)
                    continue;
                interfaceAddressStringBuilder.deleteCharAt(interfaceAddressStringBuilder.lastIndexOf(","));
                info.put("nic-" + String.valueOf(nicCount) + "-ip", interfaceAddressStringBuilder.toString());
                info.put("nic-" + String.valueOf(nicCount) + "-mtu", String.valueOf(nic.getMTU()));

            }

            //NetworkIF[] networkIFs = hardwareAbstractionLayer.getNetworkIFs();
            //networkIFs[0].
            //NetworkIF[] networkIFs = hal.getNetworkIFs();


            int index = nicStringBuilder.lastIndexOf(",");
            if(index != -1) {
                nicStringBuilder.deleteCharAt(index);
            }

            info.put("nic-map", nicStringBuilder.toString());
        }
        catch(Exception ex) {
            logger.error("getNetworkInterfaces() " + ex.getMessage(), ex);
        }
        return info;
    }

}