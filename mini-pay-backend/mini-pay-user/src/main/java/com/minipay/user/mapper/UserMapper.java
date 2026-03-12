package com.minipay.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minipay.common.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试考点：
 * MyBatis Plus 的 BaseMapper 提供了通用 CRUD，不需要写 XML
 * 底层通过 JDK 动态代理生成实现类
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
