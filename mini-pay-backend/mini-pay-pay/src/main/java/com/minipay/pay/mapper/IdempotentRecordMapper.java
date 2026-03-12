package com.minipay.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minipay.common.entity.IdempotentRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotentRecordMapper extends BaseMapper<IdempotentRecord> {
}
