package com.example.flashticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tacket_stock")
public class TicketStock {
    @TableId(type = IdType.AUTO)
    //票档Id
    private Integer id;
    //活动id
    private Long eventId;
    //票档昵称
    private String tierName;
    //单价
    private BigDecimal price;
    //初始库存
    private Integer totalStock;
    //目前库存
    private Integer availableStock;
    /// 版本号
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
