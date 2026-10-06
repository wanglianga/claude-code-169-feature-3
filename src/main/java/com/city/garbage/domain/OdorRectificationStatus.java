package com.city.garbage.domain;

/** 异味反复整改单状态 */
public enum OdorRectificationStatus {
    RECTIFYING,       // 整改中（消杀/桶体清洗/排风检查/清运复盘任务待完成）
    OBSERVING,        // 观察期（整改完成，持续观察异味曲线/投诉/满溢，等待复查）
    ESCALATED,        // 升级深度排查（清洗后仍超标，需检查排风/桶体密封/清运频次）
    CLOSED_EFFECTIVE  // 整改有效，闭环
}
