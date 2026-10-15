package dev.answer.campuswater.api;

import org.json.JSONObject;

/**
 * 设备档案，来自 {@code /device/list/mac}。
 *
 * <p>字段名做了多路兜底，因为小程序里同一个逻辑字段有不止一种命名
 * （见 app-service.js:71147、71187、71193、71199）。
 */
public class DeviceRecord {

    public String devId = "";          // DevID / devID
    public int bigTypeId;              // Dsbtypeid / bigDeviceType / bigTypeId
    public int smallTypeId;            // DevTypeID / smallDeviceType / smallTypeId
    public String projectId = "";      // PrjID / projectId
    public String deviceName = "";     // DevName / deviceName
    public int communicationTypeId;    // 0 = 纯蓝牙；4/5 = 联网(SN)
    public boolean enabled = true;     // isUse / IsUse
    public String keyNo = "";
    public String keyType = "";
    public String keyStatus = "";

    /** BLE 地址，由客户端在扫描命中后填入。 */
    public transient String bleAddress = "";

    /** 广播数据推导出的 MAC 备选值（见 BleClient 的说明）。 */
    public transient String advMac = "";

    public static DeviceRecord fromJson(JSONObject o) {
        DeviceRecord d = new DeviceRecord();
        d.devId = firstNonEmpty(o, "DevID", "devID", "devId");
        d.bigTypeId = firstInt(o, "Dsbtypeid", "bigDeviceType", "bigTypeId");
        d.smallTypeId = firstInt(o, "DevTypeID", "smallDeviceType", "smallTypeId");
        d.projectId = firstNonEmpty(o, "PrjID", "projectId");
        d.deviceName = firstNonEmpty(o, "DevName", "deviceName");
        d.communicationTypeId = firstInt(o, "communicationTypeId", "devTypeStatus");
        d.enabled = firstBool(o, "isUse", "IsUse");
        d.keyNo = o.optString("keyNo", "");
        d.keyType = o.optString("keyType", "");
        d.keyStatus = o.optString("keyStatus", "");
        return d;
    }

    /** 是否走蓝牙链路。communicationTypeId != 0 表示联网型号，与本应用无关。 */
    public boolean isBluetooth() {
        return communicationTypeId == 0;
    }

    public String displayName() {
        String n = deviceName == null || deviceName.isEmpty() ? ("设备 " + devId) : deviceName;
        return n + "\n" + ApiConfig.bigTypeName(bigTypeId) + " · " + bleAddress;
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }

    private static int firstInt(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                return o.optInt(k, 0);
            }
        }
        return 0;
    }

    private static boolean firstBool(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                return o.optBoolean(k, true);
            }
        }
        return true;
    }
}
