package com.example.flashticket.controller;

import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.dto.TicketDtos;
import com.example.flashticket.service.TicketEventService;
import com.example.flashticket.vo.TickVos;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class TicketEventController {
    @Resource
    private TicketEventService ticketEventService;
    @PostMapping
    public ApiResponse<TickVos.EventVo> create(@Valid @RequestBody TicketDtos.CreateEventRequest r) {
        return ApiResponse.success(ticketEventService.createEvent(r));
    }
    @GetMapping("/{id}")
    public ApiResponse<TickVos.EventVo> get(@PathVariable Long id){
        return ApiResponse.success(ticketEventService.get(id));
    }
    @GetMapping
    public ApiResponse<TickVos.PageResult<TickVos.EventVo>>page(@RequestParam(defaultValue = "1") long page,@RequestParam(defaultValue = "10") long size){
        return ApiResponse.success(ticketEventService.page(page,size));
    }
    @PutMapping("/{id}")
    public ApiResponse<TickVos.EventVo> update(@PathVariable Long id, @Valid @RequestBody TicketDtos.UpdateEventRequest updateEventRequest){
        return ApiResponse.success(ticketEventService.update(id,updateEventRequest));
    }
    @PutMapping("/{id}/status")
    public ApiResponse<TickVos.EventVo> changStatus(@PathVariable Long id, @Valid @RequestBody TicketDtos.ChangeEventStatusRequest r){
        return ApiResponse.success(ticketEventService.changeStatus(id,r));
    }
}
