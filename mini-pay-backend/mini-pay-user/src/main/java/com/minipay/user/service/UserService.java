package com.minipay.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minipay.common.dto.LoginRequest;
import com.minipay.common.entity.User;
import com.minipay.common.util.JwtUtil;
import com.minipay.user.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 面试考点：
 * 1. BCrypt 是单向哈希 + 随机盐，同一密码每次加密结果不同
 * 2. 为什么不用 MD5？ MD5 可被彩虹表破解，BCrypt 有盐值 + 自适应成本因子
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 用户注册
     */
    public void register(LoginRequest request) {
        // 检查用户名是否已存在
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (existing != null) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword())); // BCrypt 加密
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
    }

    /**
     * 用户登录，返回 JWT Token
     */
    public Map<String, Object> login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // BCrypt 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // 生成 JWT
        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("userId", user.getId());
        return result;
    }

    /**
     * 获取用户信息
     */
    public User getUserById(Long id) {
        User user = userMapper.selectById(id);
        if (user != null) {
            user.setPassword(null); // 不返回密码
        }
        return user;
    }
}
