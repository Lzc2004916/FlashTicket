package com.example.flashticket.controller;

import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.dto.TicketDtos;
import com.example.flashticket.service.TicketStockService;
import com.example.flashticket.vo.TickVos;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events/{eventId}/stocks")
public class TickStockController {
    @Resource
    private TicketStockService ticketStockService;
    @PostMapping
    public ApiResponse<TickVos.StockVo> create(@PathVariable Long eventId, @RequestBody TicketDtos.CreateStockRequest createStockRequest){
        return ApiResponse.success(ticketStockService.create(eventId,createStockRequest));
    }
}
