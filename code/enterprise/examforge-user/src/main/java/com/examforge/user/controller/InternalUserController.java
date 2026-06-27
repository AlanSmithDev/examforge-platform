package com.examforge.user.controller;

import com.examforge.common.web.Result;
import com.examforge.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 内部统计（Feign：仅内网，网关不路由 /internal/**） */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserMapper userMapper;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(Map.of("users", userMapper.selectCount(null)));
    }
}
