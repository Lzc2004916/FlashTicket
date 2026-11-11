package com.example.flashticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
@Data
@TableName("ticket_event")
@Builder
public class TicketEvent {
    @TableId(type = IdType.AUTO)
    /// 活动Id
    private Long id;
    /// 活动名
    private String name;
    /// 活动地点
    private String venue;
    /// 开始时间
    private LocalDateTime saleStartTime;
    /// 结束时间
    private LocalDateTime saleEndTime;
    /// 状态
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
