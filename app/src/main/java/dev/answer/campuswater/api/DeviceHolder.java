package dev.answer.campuswater.api;

/** 在 Activity 之间传递当前选中的设备。 */
public final class DeviceHolder {

    private static DeviceRecord current;

    private DeviceHolder() {
    }

    public static void set(DeviceRecord device) {
        current = device;
    }

    public static DeviceRecord get() {
        return current;
    }
}
