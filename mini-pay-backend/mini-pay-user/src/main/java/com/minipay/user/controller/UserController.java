package com.minipay.user.controller;

import com.minipay.common.dto.LoginRequest;
import com.minipay.common.entity.User;
import com.minipay.common.result.R;
import com.minipay.common.util.JwtUtil;
import com.minipay.user.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public R<Void> register(@RequestBody LoginRequest request) {
        userService.register(request);
        return R.ok();
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest request) {
        Map<String, Object> result = userService.login(request);
        return R.ok(result);
    }

    /**
     * 获取当前登录用户信息
     * Token 由 Gateway 解析后，将 userId 放入请求头传递下来
     */
    @GetMapping("/info")
    public R<User> info(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(userService.getUserById(userId));
    }
}
