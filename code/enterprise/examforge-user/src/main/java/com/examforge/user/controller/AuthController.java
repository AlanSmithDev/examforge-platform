package com.examforge.user.controller;

import com.examforge.common.web.GlobalExceptionHandler.BizException;
import com.examforge.common.web.Result;
import com.examforge.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 认证接口（网关白名单放行） */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        return authService.register(body.get("mobile"), body.get("password"), body.get("nickname"), body.get("role"), body.get("inviteCode"));
    }

    /** 客户端 IP：网关 X-Forwarded-For 首段（直连时回退 X-Real-IP/unknown） */
    private String clientIp(jakarta.servlet.http.HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String real = req.getHeader("X-Real-IP");
        return real == null || real.isBlank() ? "unknown" : real.trim();
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(jakarta.servlet.http.HttpServletRequest req,
                                             @RequestBody Map<String, String> body) {
        return authService.login(body.get("mobile"), body.get("password"), clientIp(req));
    }

    @PostMapping("/admin-login")
    public Result<Map<String, Object>> adminLogin(jakarta.servlet.http.HttpServletRequest req,
                                                  @RequestBody Map<String, String> body) {
        Result<Map<String, Object>> r = authService.login(body.get("mobile"), body.get("password"), clientIp(req));
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) r.getData().get("profile");
        String role = String.valueOf(profile.get("role"));
        if (!"SUPER_ADMIN".equals(role) && !"OP".equals(role) && !"EDITOR".equals(role)) {
            throw new BizException(Result.UNAUTHORIZED, "该账号无管理端权限");
        }
        return r;
    }

    /** 当前用户（网关透传 X-User-Id） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader(value = "X-User-Id", required = false) String uid) {
        if (uid == null) throw new BizException(Result.UNAUTHORIZED, "未登录");
        return Result.ok(Map.of("uid", Long.valueOf(uid)));
    }
}
