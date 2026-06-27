package com.examforge.api.feign;

import com.examforge.api.dto.BillingDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** 交易服务 Feign 契约（paper 导出时的计费闭环，docs/14 §6） */
@FeignClient(name = "examforge-trade", path = "/api/v1")
public interface TradeClient {

    @GetMapping("/trade/billing")
    BillingDTO billing(@RequestHeader("X-User-Id") String userId,
                       @RequestParam("questionCount") int questionCount,
                       @RequestParam("paperHash") String paperHash);

    @PostMapping("/trade/consume")
    Map<String, Object> consume(@RequestHeader("X-User-Id") String userId,
                                @RequestBody Map<String, Object> body);

    /** 权益判定（会员是否有效/点数/免费额度），AI 配额治理使用（docs/15 AI-4） */
    @GetMapping("/member/me")
    Map<String, Object> entitlement(@RequestHeader("X-User-Id") String userId);

    /** 纠错采纳奖励点数（feedback 闭环，docs/10） */
    @PostMapping("/internal/trade/points/reward")
    Map<String, Object> reward(@RequestHeader("X-User-Id") String userId,
                               @RequestBody Map<String, Object> body);

    /** 资源下载等场景的点数扣减（resource 服务计费闭环，docs/26 F-XKW-02） */
    @PostMapping("/internal/trade/points/deduct")
    Map<String, Object> deduct(@RequestHeader("X-User-Id") String userId,
                               @RequestBody Map<String, Object> body);
}
