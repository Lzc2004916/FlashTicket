package com.example.flashticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashticket.dto.TicketDtos;
import com.example.flashticket.entity.TicketStock;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.mapper.TicketEventMapper;
import com.example.flashticket.mapper.TicketStockMapper;
import com.example.flashticket.vo.TickVos;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketStockService {
    @Resource
    private TicketStockMapper ticketStockMapper;
    @Resource
    private TicketEventService ticketEventService;
    public TickVos.StockVo create(Long eventId, TicketDtos.CreateStockRequest r) {
        ticketEventService.requireEvent(eventId);
        Long count = ticketStockMapper.selectCount(new LambdaQueryWrapper<TicketStock>()
            .eq(TicketStock::getEventId, eventId)
            .eq(TicketStock::getTierName, r.tierName())
        );
        if (count > 0) throw new BusinessException(409,"同一活动不能有重名票档");
        TicketStock s = new TicketStock();
        s.setEventId(eventId); s.setTierName(r.tierName());
        s.setPrice(r.price()); s.setTotalStock(r.totalStock()); s.setAvailableStock(r.totalStock()); s.setVersion(0);
        return toVo(s);
    }
    public List<TickVos.StockVo> listByEvent(Long eventId){
        ticketEventService.requireEvent(eventId);
        List<TicketStock> ticketStocks = ticketStockMapper.selectList(new LambdaQueryWrapper<TicketStock>()
            .eq(TicketStock::getEventId, eventId)
            .orderByAsc(TicketStock::getId));
        return ticketStocks.stream().map(this::toVo).toList();
    }
    private TicketStock requireStock(Long id){
        TicketStock ticketStock = ticketStockMapper.selectById(id);
        if (ticketStock == null) throw new BusinessException(404,"票档不存在");
        return ticketStock;
    }
    private TickVos.StockVo toVo(TicketStock s){
        return new TickVos.StockVo(s.getId(),s.getEventId(),s.getTierName(),s.getPrice(),s.getTotalStock(),s.getAvailableStock());
    }
}
