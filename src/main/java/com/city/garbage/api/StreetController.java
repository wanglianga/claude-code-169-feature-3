package com.city.garbage.api;

import com.city.garbage.domain.OdorRectification;
import com.city.garbage.service.OdorRectificationService;
import com.city.garbage.service.StreetAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 街道端：按小区查看分类质量与投诉趋势；观察异味反复整改全过程 */
@Tag(name = "街道端", description = "小区分类质量、错投率、投诉趋势、活跃联动单、异味反复整改观察")
@RestController
@RequestMapping("/api/street")
@RequiredArgsConstructor
public class StreetController {

    private final StreetAnalyticsService analyticsService;
    private final OdorRectificationService odorRectificationService;

    @Operation(summary = "全街道各小区分类质量对比")
    @GetMapping("/overview")
    public List<Map<String, Object>> overview(@RequestParam(defaultValue = "7") int days) {
        return analyticsService.overview(days);
    }

    @Operation(summary = "单个小区分类质量与投诉趋势（按天）")
    @GetMapping("/communities/{communityCode}/quality")
    public Map<String, Object> quality(@PathVariable String communityCode,
                                       @RequestParam(defaultValue = "7") int days) {
        return analyticsService.communityQuality(communityCode, days);
    }

    @Operation(summary = "异味整改单列表（含整改责任人与复查时间，可按垃圾房过滤）")
    @GetMapping("/odor-rectifications")
    public List<OdorRectification> odorRectifications(@RequestParam(required = false) String roomCode) {
        return odorRectificationService.listForStreet(roomCode);
    }

    @Operation(summary = "异味整改单详情：整改回执（照片/设备读数）、异味曲线、整改前后对比、观察期投诉与满溢次数")
    @GetMapping("/odor-rectifications/{id}")
    public Map<String, Object> odorRectificationDetail(@PathVariable Long id) {
        return odorRectificationService.detailView(id);
    }

    @Operation(summary = "垃圾房异味曲线（观察整改后异味是否下降）")
    @GetMapping("/rooms/{roomCode}/odor-curve")
    public List<Map<String, Object>> odorCurve(@PathVariable String roomCode,
                                               @RequestParam(defaultValue = "7") int days) {
        return odorRectificationService.odorCurve(roomCode, days);
    }
}
