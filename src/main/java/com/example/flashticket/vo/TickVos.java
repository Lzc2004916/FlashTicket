package com.example.flashticket.vo;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public final class TickVos {
    private TickVos(){}
    public record EventVo(Long id, String name, String venue,
                          LocalDateTime saleStartTime,LocalDateTime saleEndTime,Integer status){}
    public record StockVo(Long id, Long eventId, String tierName, BigDecimal price,
                          Integer totalStock,Integer availableStock){}
    public record PageResult<T>(long total, long page, long size, List<T> records){}
}
