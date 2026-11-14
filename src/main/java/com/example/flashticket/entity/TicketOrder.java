package com.example.flashticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ticket_order")
public class TicketOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long eventId;
    private Long stockId;
    private String eventName;
    private String tierName;
    /// 数量
    private BigDecimal amount;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
