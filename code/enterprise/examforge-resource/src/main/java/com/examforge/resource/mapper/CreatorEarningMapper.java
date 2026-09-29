package com.examforge.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examforge.resource.domain.CreatorEarning;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CreatorEarningMapper extends BaseMapper<CreatorEarning> {

    /** 累计分成（合计按全部流水口径，不随分页截断） */
    @Select("SELECT COALESCE(SUM(share_cents), 0) FROM creator_earning WHERE creator_user_id = #{uid}")
    long sumShare(@Param("uid") Long uid);
}
