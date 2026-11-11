package com.example.flashticket.service;

import com.example.flashticket.dto.TicketDtos;
import com.example.flashticket.entity.TicketEvent;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.mapper.TicketEventMapper;
import com.example.flashticket.vo.TickVos;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import com.example.flashticket.vo.TickVos.EventVo;          // 直接导入嵌套类型
import com.example.flashticket.dto.TicketDtos.CreateEventRequest;

import java.time.LocalDateTime;

@Service
public class TicketEventService {

    @Resource
    private TicketEventMapper ticketEventMapper;

    public EventVo createEvent(CreateEventRequest r) {
        checkTime(r.saleStartTime(),r.saleEndTime());
        TicketEvent result = TicketEvent.builder()
            .name(r.name())
            .venue(r.venue())
            .saleStartTime(r.saleStartTime())
            .saleEndTime(r.saleEndTime())
            .status(0)
            .build();
        ticketEventMapper.insert(result);
        return toVo(result);
    }
    private void checkTime(LocalDateTime start,LocalDateTime end) {
        if (!end.isAfter(start)) throw new BusinessException(400,"结束时间晚于开始时间");
    }
    private TickVos.EventVo toVo(TicketEvent e){
        return new TickVos.EventVo(e.getId(), e.getName(), e.getVenue(), e.getSaleStartTime(), e.getSaleEndTime(), e.getStatus());
    }
}
