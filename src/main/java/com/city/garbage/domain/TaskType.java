package com.city.garbage.domain;

/** 现场治理任务类型 */
public enum TaskType {
    CLEANING("保洁"),
    DISINFECTION("消杀"),
    TRUCK_DISPATCH("清运调度"),
    REPAIR("设备维修"),
    RECHECK_SORTING("错投现场复核"),
    RECTIFY("整改复核"),
    BIN_DEEP_CLEAN("桶体清洗"),
    EXHAUST_CHECK("排风设备检查"),
    COLLECTION_REVIEW("清运频次复盘");

    private final String label;
    TaskType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
