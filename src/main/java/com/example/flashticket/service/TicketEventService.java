package com.example.flashticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flashticket.entity.TicketEvent;
import com.example.flashticket.entity.TicketStock;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.mapper.TicketEventMapper;
import com.example.flashticket.mapper.TicketStockMapper;
import com.example.flashticket.vo.TickVos;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import com.example.flashticket.vo.TickVos.EventVo;          // 直接导入嵌套类型
import com.example.flashticket.dto.TicketDtos.CreateEventRequest;
import com.example.flashticket.dto.TicketDtos.UpdateEventRequest;
import com.example.flashticket.dto.TicketDtos.ChangeEventStatusRequest;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TicketEventService {

    @Resource
    private TicketEventMapper ticketEventMapper;
    @Resource
    private TicketStockMapper ticketStockMapper;

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
    //根据ID获取活动消息
    public EventVo get(Long id){
        if (id == null || id.intValue() == 0) throw new BusinessException(405,"参数错误");
        return toVo(requireEvent(id));
    }
    /// 分页
    public TickVos.PageResult<EventVo> page(long page,long size){
        Page<TicketEvent> result = ticketEventMapper.selectPage(Page.of(page, Math.min(size, 100)), null);
        List<EventVo> records = result.getRecords().stream().map(this::toVo).toList();
        return new TickVos.PageResult<>(result.getTotal(),result.getCurrent(),result.getSize(),records);
    }
    public EventVo update(Long id, UpdateEventRequest r) {
        checkTime(r.saleStartTime(),r.saleEndTime());
        TicketEvent e = requireEvent(id);
        e.setName(r.name());
        e.setVenue(r.venue());
        e.setSaleStartTime(r.saleStartTime());
        e.setSaleEndTime(r.saleEndTime());
        ticketEventMapper.updateById(e);
        return toVo(e);
    }
    public EventVo changeStatus(Long id, ChangeEventStatusRequest e){
        TicketEvent ticketEvent = requireEvent(id);
        Long count = ticketStockMapper.selectCount(new LambdaQueryWrapper<TicketStock>().eq(TicketStock::getEventId, id));
        if (ticketEvent.getStatus() == 1 && count == 0) throw new BusinessException(400,"至少创建一个票档后才能上架");
        ticketEvent.setStatus(e.status());
        ticketEventMapper.updateById(ticketEvent);
        return toVo(ticketEvent);
    }
    public TicketEvent requireEvent(Long id){
        TicketEvent selectById = ticketEventMapper.selectById(id);
        if (selectById == null) throw new BusinessException(404,"活动不存在");
        return selectById;
    }
    private void checkTime(LocalDateTime start,LocalDateTime end) {
        if (!end.isAfter(start)) throw new BusinessException(400,"结束时间晚于开始时间");
    }
    private TickVos.EventVo toVo(TicketEvent e){
        return new TickVos.EventVo(e.getId(), e.getName(), e.getVenue(), e.getSaleStartTime(), e.getSaleEndTime(), e.getStatus());
    }
}
