package com.example.flashticket.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class OrderVos {
    private OrderVos() {}
    public record UserVo(Long id,String username,String phone,Integer status){}
    public record OrderVo(Long id, String orderNo, Long userId, Long eventId, Long stockId,
                          String eventName, String tierName, BigDecimal amount, Integer status, LocalDateTime createdAt) {}
}
