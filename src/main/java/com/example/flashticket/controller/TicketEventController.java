package com.example.flashticket.controller;

import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.dto.TicketDtos;
import com.example.flashticket.service.TicketEventService;
import com.example.flashticket.vo.TickVos;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class TicketEventController {
    @Resource
    private TicketEventService ticketEventService;
    @PostMapping
    public ApiResponse<TickVos.EventVo> create(@Valid @RequestBody TicketDtos.CreateEventRequest r) {
        return ApiResponse.success(ticketEventService.createEvent(r));
    }
}
