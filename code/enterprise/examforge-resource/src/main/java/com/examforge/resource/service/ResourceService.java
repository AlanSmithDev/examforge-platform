package com.examforge.resource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examforge.api.feign.TradeClient;
import com.examforge.common.web.GlobalExceptionHandler.BizException;
import com.examforge.common.web.Result;
import com.examforge.resource.domain.CopyrightAppeal;
import com.examforge.resource.domain.ResourceBasket;
import com.examforge.resource.domain.ResourceDownload;
import com.examforge.resource.domain.ResourceItem;
import com.examforge.resource.logic.ResourceRules;
import com.examforge.resource.mapper.CopyrightAppealMapper;
import com.examforge.resource.mapper.ResourceBasketMapper;
import com.examforge.resource.mapper.ResourceDownloadMapper;
import com.examforge.resource.mapper.ResourceItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 资源中心核心服务（docs/26 F-XKW-01/02/03/14） */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceItemMapper itemMapper;
    private final ResourceBasketMapper basketMapper;
    private final ResourceDownloadMapper downloadMapper;
    private final CopyrightAppealMapper appealMapper;
    private final TradeClient tradeClient;

    // ---------- 浏览 ----------

    public Page<ResourceItem> page(Integer stage, String subject, String category, String level,
                                   String keyword, long page, long size) {
        LambdaQueryWrapper<ResourceItem> w = new LambdaQueryWrapper<ResourceItem>()
                .eq(ResourceItem::getStatus, 1)
                .eq(stage != null, ResourceItem::getStage, stage)
                .eq(subject != null && !subject.isBlank(), ResourceItem::getSubject, subject)
                .eq(category != null && !category.isBlank(), ResourceItem::getCategory, category)
                .eq(level != null && !level.isBlank(), ResourceItem::getLevel, level)
                .like(keyword != null && !keyword.isBlank(), ResourceItem::getTitle, keyword)
                .orderByDesc(ResourceItem::getId);
        return itemMapper.selectPage(new Page<>(page, Math.min(100, Math.max(1, size))), w);
    }

    /** 详情：浏览计数 + 预览信息（免费预览 33%，docs/26 F-XKW-02） */
    public Map<String, Object> detail(Long id) {
        ResourceItem r = itemMapper.selectById(id);
        if (r == null) throw new BizException(Result.NOT_FOUND, "资源不存在");
        itemMapper.incrBrowse(id);
        int totalPages = r.getPages() == null ? 0 : r.getPages();
        int freePages = ResourceRules.previewFreePages(totalPages,
                r.getPreviewFreePct() == null ? 33 : r.getPreviewFreePct());
        return Map.of("item", r,
                "preview", Map.of("totalPages", totalPages, "freePages", freePages,
                        "lockedPages", Math.max(0, totalPages - freePages)));
    }

    // ---------- 资源篮 ----------

    public Map<String, Object> addToBasket(Long uid, Long resourceId) {
        ResourceItem r = itemMapper.selectById(resourceId);
        if (r == null || r.getStatus() != 1) throw new BizException(Result.BAD_REQUEST, "资源不存在或未上架");
        if (basketMapper.insertIgnore(uid, resourceId) == 0) {
            throw new BizException(Result.BAD_REQUEST, "已在资源篮中");
        }
        return Map.of("ok", true);
    }

    public Map<String, Object> removeFromBasket(Long uid, Long resourceId) {
        basketMapper.delete(new LambdaQueryWrapper<ResourceBasket>()
                .eq(ResourceBasket::getUserId, uid).eq(ResourceBasket::getResourceId, resourceId));
        return Map.of("ok", true);
    }

    public List<ResourceItem> basket(Long uid) {
        List<Long> ids = basketMapper.selectList(new LambdaQueryWrapper<ResourceBasket>()
                        .eq(ResourceBasket::getUserId, uid).orderByDesc(ResourceBasket::getId))
                .stream().map(ResourceBasket::getResourceId).toList();
        return ids.isEmpty() ? List.of() : itemMapper.selectBatchIds(ids);
    }

    // ---------- 计费下载（docs/26 F-XKW-02：判价→扣点→落账→计数） ----------

    @Transactional
    public Map<String, Object> download(Long uid, Long resourceId) {
        ResourceItem r = itemMapper.selectById(resourceId);
        if (r == null || r.getStatus() != 1) throw new BizException(Result.NOT_FOUND, "资源不存在或未上架");
        boolean owned = downloadMapper.selectCount(new LambdaQueryWrapper<ResourceDownload>()
                .eq(ResourceDownload::getUserId, uid)
                .eq(ResourceDownload::getResourceId, resourceId)) > 0;
        int balance = entitlementBalance(uid);
        ResourceRules.Mode mode = ResourceRules.decide(r.getLevel(), owned, balance,
                r.getPriceCents() == null ? 0 : r.getPriceCents());
        if (mode == ResourceRules.Mode.INSUFFICIENT) {
            throw new BizException(Result.TOO_MANY, "点数不足，请充值后下载");
        }
        int charged = 0;
        if (mode == ResourceRules.Mode.POINTS) {
            charged = r.getPriceCents();
            tradeClient.deduct(String.valueOf(uid),
                    Map.of("points", charged, "reason", "RESOURCE", "ref", String.valueOf(resourceId)));
        }
        ResourceDownload d = new ResourceDownload();
        d.setUserId(uid);
        d.setResourceId(resourceId);
        d.setMode(mode == ResourceRules.Mode.POINTS ? "POINTS" : "FREE");
        d.setPriceCents(charged);
        d.setCreatedAt(LocalDateTime.now());
        downloadMapper.insert(d);
        itemMapper.incrDownload(resourceId);
        log.info("资源下载: user={} resource={} mode={}", uid, resourceId, mode);
        return Map.of("mode", mode.name(), "charged", charged, "fileKey", r.getFileKey() == null ? "" : r.getFileKey());
    }

    /** 点数余额（trade 不可用时按 0 处理 → 走扣点失败兜底，不让资源站整体不可用） */
    private int entitlementBalance(Long uid) {
        try {
            Object v = tradeClient.entitlement(String.valueOf(uid)).get("pointBalance");
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            log.warn("权益查询失败，按 0 余额处理: {}", e.getMessage());
            return 0;
        }
    }

    public List<ResourceDownload> myDownloads(Long uid) {
        return downloadMapper.selectList(new LambdaQueryWrapper<ResourceDownload>()
                .eq(ResourceDownload::getUserId, uid).orderByDesc(ResourceDownload::getId));
    }

    // ---------- 版权异议/申诉（docs/26 F-XKW-14） ----------

    public Map<String, Object> submitAppeal(Long uid, String targetType, Long targetId,
                                            String appealType, String content, String contact) {
        validateTarget(targetType);
        if (appealType == null || appealType.isBlank()) appealType = "OTHER";
        if (content == null || content.length() < 5) {
            throw new BizException(Result.BAD_REQUEST, "请描述具体问题（至少 5 字）");
        }
        CopyrightAppeal a = new CopyrightAppeal();
        a.setUserId(uid);
        a.setTargetType(targetType);
        a.setTargetId(targetId == null ? 0 : targetId);
        a.setAppealType(appealType);
        a.setContent(content);
        a.setContact(contact);
        a.setStatus(ResourceRules.APPEAL_OPEN);
        a.setCreatedAt(LocalDateTime.now());
        appealMapper.insert(a);
        return Map.of("id", a.getId(), "status", a.getStatus());
    }

    public Map<String, Object> withdrawAppeal(Long uid, Long appealId) {
        CopyrightAppeal a = appealMapper.selectById(appealId);
        if (a == null || !a.getUserId().equals(uid)) throw new BizException(Result.NOT_FOUND, "申诉不存在");
        ResourceRules.mustTransition(a.getStatus(), ResourceRules.APPEAL_WITHDRAWN);
        a.setStatus(ResourceRules.APPEAL_WITHDRAWN);
        a.setResolvedAt(LocalDateTime.now());
        appealMapper.updateById(a);
        return Map.of("ok", true, "status", a.getStatus());
    }

    public List<CopyrightAppeal> myAppeals(Long uid) {
        return appealMapper.selectList(new LambdaQueryWrapper<CopyrightAppeal>()
                .eq(CopyrightAppeal::getUserId, uid).orderByDesc(CopyrightAppeal::getId));
    }

    @Transactional
    public Map<String, Object> handleAppeal(Long appealId, String handler, String action, String remark) {
        CopyrightAppeal a = appealMapper.selectById(appealId);
        if (a == null) throw new BizException(Result.NOT_FOUND, "申诉不存在");
        String to = switch (action == null ? "" : action) {
            case "RESOLVE" -> ResourceRules.APPEAL_RESOLVED;
            case "REJECT" -> ResourceRules.APPEAL_REJECTED;
            default -> throw new BizException(Result.BAD_REQUEST, "action 须为 RESOLVE/REJECT");
        };
        ResourceRules.mustTransition(a.getStatus(), to);   // 终态重复处理直接拒绝
        a.setStatus(to);
        a.setHandler(handler);
        a.setHandleRemark(remark);
        a.setResolvedAt(LocalDateTime.now());
        appealMapper.updateById(a);
        return Map.of("ok", true, "status", to);
    }

    private void validateTarget(String targetType) {
        if (!"RESOURCE".equals(targetType) && !"QUESTION".equals(targetType) && !"PAPER".equals(targetType)) {
            throw new BizException(Result.BAD_REQUEST, "targetType 须为 RESOURCE/QUESTION/PAPER");
        }
    }
}
