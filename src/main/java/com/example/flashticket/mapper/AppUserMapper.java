package com.example.flashticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashticket.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository("appUserMapper")
public interface AppUserMapper extends BaseMapper<AppUser> {
}
