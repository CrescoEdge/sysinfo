package io.cresco.sysinfo;

import com.google.gson.Gson;
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

import java.io.PrintWriter;
import java.io.StringWriter;
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

    public SysInfoBuilder(PluginBuilder plugin) {
        gson = new Gson();

        this.logger = plugin.getLogger(SysInfoBuilder.class.getName(),CLogger.Level.Info);

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

            logger.error("SysInfoBuilder : getSysInfoMap : Error : " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
            logger.error("SysInfoBuilder : getSysInfoMap : Error : " + e.getMessage());
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
        }
        return jsonInfo;
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
            logger.error("getProcessInfo() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
            logger.error("getFSInfo: " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
            logger.error("getOSInfo() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
            logger.error("getMemoryInfo() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());

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
            logger.error("getPartitionInfo() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
            logger.error("getDiskInfo() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
            logger.error("getNetworkInfo() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
            logger.error("getCPUInfo() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
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
                        System.out.println("SysInfoBuilder : Constructor : nicLoop : addrLoop : Error : " + e.getMessage());
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
            logger.error("getNetworkInterfaces() " + ex.getMessage());
            StringWriter errors = new StringWriter();
            ex.printStackTrace(new PrintWriter(errors));
            logger.error(errors.toString());
        }
        return info;
    }

}
