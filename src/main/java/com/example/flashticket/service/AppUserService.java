package com.example.flashticket.service;

import com.example.flashticket.dto.OrderDtos;
import com.example.flashticket.mapper.AppUserMapper;
import com.example.flashticket.vo.OrderVos;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class AppUserService {
    @Resource(name = "appUserMapper")
    private AppUserMapper mapper;
    public OrderVos.UserVo create(OrderDtos.CreateUserRequest createUserRequest){

    }
}
