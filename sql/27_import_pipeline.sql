-- ============================================================================
-- 27-题库数据入库辅助表 DDL（独立库 zhijuan_ingest，与生产库隔离，不分片）
-- 配套文档：docs/27-题库数据入库与海量数据优化方案.md
-- 字符集 utf8mb4 / 时区统一 +08:00 / MySQL 8.0+
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `zhijuan_ingest`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `zhijuan_ingest`;

-- ----------------------------------------------------------------------------
-- 1. 批次登记表：每一次导入（数据集/爬取/UGC/AI）一个批次，全链路状态机
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ingest_batch` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `batch_no`      VARCHAR(32)  NOT NULL COMMENT '批次号 B{yyyymmdd}-{seq}',
  `source_type`   TINYINT      NOT NULL COMMENT '1公开数据集 2爬取 3UGC上传 4AI生成 5人工录入',
  `source_ref`    JSON         NULL COMMENT '来源明细: 数据集名/url/上传人/模型版本',
  `file_manifest` JSON         NOT NULL COMMENT '文件清单[{path,rows,sha256}]',
  `expect_rows`   INT          NOT NULL DEFAULT 0 COMMENT '期望行数(对账基准)',
  `stage`         VARCHAR(16)  NOT NULL DEFAULT 'LOADED'
                  COMMENT 'LOADED/CLEANED/NORMALIZED/DEDUPED/QUALITY/MERGED/ARCHIVED/FAIL',
  `failed_step`   VARCHAR(16)  NULL COMMENT 'FAIL 时停留的步骤(重放起点)',
  `rule_versions` JSON         NULL COMMENT '清洗/打标规则版本号(可复现)',
  `artifact_uri`  JSON         NULL COMMENT '各步骤中间产物URI(本地/MinIO), 支持单步重放',
  `stats`         JSON         NULL COMMENT '各阶段计数/耗时/工单数快照',
  `operator`      VARCHAR(64)  NOT NULL DEFAULT 'system',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`),
  KEY `idx_stage` (`stage`, `create_time`)
) ENGINE=InnoDB COMMENT='入库批次登记(状态机+对账+回放)';

-- ----------------------------------------------------------------------------
-- 2. staging 暂存表：源数据原样落地 + 规范化结果 + 指纹，生产库唯一入口
--    装载速率优化：宽 TEXT/JSON、最小索引（唯一键 + 指纹），导入期先装后建二级索引
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ingest_staging_question` (
  `id`              BIGINT      NOT NULL AUTO_INCREMENT,
  `batch_id`        BIGINT      NOT NULL,
  `source_qid`      VARCHAR(64) NOT NULL COMMENT '源唯一键(32hex或ZQ-格式,幂等键)',
  `raw`             JSON        NOT NULL COMMENT '源行原样(JSONL整行/CSV行), 永不丢弃',
  `stage_id`        TINYINT     NULL COMMENT '规范化:学段枚举(小学1/初中2/高中3/大学4/考研5/中职6)',
  `subject_raw`     VARCHAR(50) NULL COMMENT '源学科名(未对齐字典前)',
  `subject_id`      SMALLINT    NULL COMMENT '对齐后学科ID(NULL=待人工)',
  `question_type`   TINYINT     NULL COMMENT '规范化题型枚举',
  `sub_type`        VARCHAR(30) NULL COMMENT '细分题型(填空-单空/多选-3答案…)',
  `difficulty`      TINYINT     NULL COMMENT '1易~5难(分箱)',
  `difficulty_coef` DECIMAL(4,2) NULL COMMENT '原始难度系数0~1(得分率口径)',
  `stem_text`       MEDIUMTEXT  NULL COMMENT '清洗后题干(LaTeX保留)',
  `options_json`    JSON        NULL COMMENT '规范化选项[{label,content}]',
  `answer_json`     JSON        NULL COMMENT '规范化答案',
  `analysis_json`   JSON        NULL COMMENT '规范化解析(五段式分块)',
  `kp_tmp_json`     JSON        NULL COMMENT '源知识点路径数组(暂存,待归并)',
  `year`            SMALLINT    NULL,
  `region_code`     VARCHAR(10) NULL,
  `quality_src`     CHAR(1)     NULL COMMENT '源质量级 A/B/C/raw',
  `norm_stem_hash`  CHAR(32)    NOT NULL COMMENT '题干精确指纹md5(去空白标点变量)',
  `simhash64`       BIGINT UNSIGNED NULL COMMENT '题干近似指纹(同科内汉明距离≤3疑似重)',
  `issue_flags`     INT         NOT NULL DEFAULT 0 COMMENT '位图:1no_answer 2short_stem 4garbled 8latex_fail 16low_ocr 32suspect_dup 64answer_conflict',
  `row_status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0待处理 1待复核 2合格 3剔除 4并题',
  `merged_into`     VARCHAR(64) NULL COMMENT '并题时指向存活题 source_qid',
  `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_qid` (`batch_id`, `source_qid`),
  KEY `idx_stem_hash` (`norm_stem_hash`),
  KEY `idx_row_status` (`row_status`, `batch_id`)
  -- 导入大批次后补建（文档 §9.9）：KEY idx_simhash (simhash64), KEY idx_subject (subject_id)
) ENGINE=InnoDB COMMENT='staging 暂存(源行不可变+规范化+指纹)';

-- ----------------------------------------------------------------------------
-- 3. qid→雪花ID 映射表：跨批次幂等 + 并题断链保护（历史组卷引用不失效）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ingest_id_map` (
  `source_qid`   VARCHAR(64) NOT NULL,
  `batch_id`     BIGINT      NOT NULL,
  `question_id`  BIGINT      NULL COMMENT '生产 question.id(雪花); 并题行为NULL',
  `merged_into`  BIGINT      NULL COMMENT '并题: 存活题生产ID(引用改挂至此)',
  `subject_id`   SMALLINT    NOT NULL,
  `status`       TINYINT     NOT NULL DEFAULT 0 COMMENT '0映射有效 1已并题',
  `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`source_qid`),
  UNIQUE KEY `uk_question` (`question_id`),
  KEY `idx_merged` (`merged_into`)
) ENGINE=InnoDB COMMENT='源qid→生产ID映射(幂等/并链)';

-- ----------------------------------------------------------------------------
-- 4. 质量工单表：机审不合格/冲突题的治理闭环（编辑后台消费）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ingest_quality_issue` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `batch_id`     BIGINT      NOT NULL,
  `source_qid`   VARCHAR(64) NOT NULL,
  `issue_type`   VARCHAR(24) NOT NULL COMMENT 'no_answer/short_stem/garbled/latex_fail/low_ocr/suspect_dup/answer_conflict/enum_pending',
  `detail`       JSON        NULL COMMENT '证据(相似题qid对/latex报错/字段快照)',
  `status`       TINYINT     NOT NULL DEFAULT 0 COMMENT '0OPEN 1FIXED 2WONTFIX 3AUTO_RESOLVED',
  `assignee`     VARCHAR(64) NULL,
  `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_type` (`status`, `issue_type`),
  KEY `idx_qid` (`source_qid`)
) ENGINE=InnoDB COMMENT='入库质量工单(OPEN→FIXED/WONTFIX)';

-- ----------------------------------------------------------------------------
-- 5. 学科字典对齐表：爬虫侧叫法 → 正式 subject（docs/04 subject 表的影子表）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ingest_subject_alias` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `alias`        VARCHAR(50) NOT NULL COMMENT '源叫法(如 政治/道德与法治/CMB学科名)',
  `stage_id`     TINYINT     NOT NULL,
  `subject_id`   SMALLINT    NULL COMMENT '正式学科ID(NULL=未对齐,进人工队列)',
  `auto_matched` TINYINT     NOT NULL DEFAULT 0 COMMENT '1规则自动匹配 0人工确认',
  `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alias_stage` (`alias`, `stage_id`)
) ENGINE=InnoDB COMMENT='学科别名对齐(源名→正式subject_id)';
