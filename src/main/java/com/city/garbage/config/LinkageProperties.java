package com.city.garbage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.linkage")
public class LinkageProperties {
    private int nightStart = 20;
    private int nightEnd = 6;
    private int nightDisposalThreshold = 3;
    private int offlineMinutes = 10;
    private int binFullThreshold = 90;
    private int odorThreshold = 70;
    private int pointsPerKg = 10;
    private int wrongSortDeduction = 5;
    /** 清运离场后多少分钟未做清洁确认视为“清运后未清洁” */
    private int notCleanedMinutes = 15;
    /** 积分待结算超过多少秒视为“积分未到账” */
    private int unsettledSeconds = 60;
    /** 异味反复整改：统计窗口（天），窗口内超标次数达到阈值即生成整改单 */
    private int odorRecurringWindowDays = 7;
    /** 异味反复整改：窗口内超标次数阈值（“多次”） */
    private int odorRecurringCount = 3;
    /** 整改反馈后默认复查时间（小时），到期由定时巡检评估整改效果 */
    private int odorReviewHours = 48;
}
