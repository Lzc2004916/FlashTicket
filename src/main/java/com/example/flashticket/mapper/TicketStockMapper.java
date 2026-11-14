package com.example.flashticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashticket.entity.TicketEvent;
import com.example.flashticket.entity.TicketStock;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketStockMapper extends BaseMapper<TicketStock> {
}
