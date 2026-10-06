package com.city.garbage.domain;

/** 异味反复整改单状态 */
public enum RectificationStatus {
    OPEN,               // 待整改（已生成消杀/桶体清洗/排风检查/清运复盘任务）
    VERIFYING,          // 观察期（整改反馈已提交，持续观察异味曲线/投诉/满溢至复查时间）
    CLOSED_IMPROVED,    // 整改有效（观察期内异味下降、无复发投诉）
    CLOSED_ESCALATED    // 仍超标已升级（生成下一轮整改，建议排风/密封/清运频次）
}
