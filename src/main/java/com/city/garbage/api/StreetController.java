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

/** 街道端：按小区查看分类质量与投诉趋势；观察异味反复整改进度与效果 */
@Tag(name = "街道端", description = "小区分类质量、错投率、投诉趋势、活跃联动单、异味整改观察")
@RestController
@RequestMapping("/api/street")
@RequiredArgsConstructor
public class StreetController {

    private final StreetAnalyticsService analyticsService;
    private final OdorRectificationService rectificationService;

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

    @Operation(summary = "异味整改单列表：整改责任人与复查时间同步到街道端")
    @GetMapping("/odor-rectifications")
    public List<OdorRectification> rectifications(@RequestParam(required = false) String roomCode) {
        return rectificationService.list(roomCode);
    }

    @Operation(summary = "整改单观察指标：观察期异味超标次数、投诉数、满溢次数及整改前后异味均值")
    @GetMapping("/odor-rectifications/{rectNo}/observation")
    public Map<String, Object> observation(@PathVariable String rectNo) {
        return rectificationService.observationStats(rectificationService.get(rectNo));
    }

    @Operation(summary = "垃圾房异味曲线：读数时间序列 + 阈值 + 关联整改单，观察整改后异味是否下降")
    @GetMapping("/rooms/{roomCode}/odor-curve")
    public Map<String, Object> odorCurve(@PathVariable String roomCode,
                                         @RequestParam(defaultValue = "7") int days) {
        return rectificationService.odorCurve(roomCode, days);
    }
}
