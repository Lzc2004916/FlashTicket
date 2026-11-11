# 第9阶段：RabbitMQ异步订单

## 1. 阶段目标

本阶段将抢票流程改为异步：请求线程只完成Redis资格预扣和消息发送，MySQL订单由RabbitMQ消费者创建。

实现顺序：

```text
1. 增加requestId和请求状态
2. 配置Exchange、Queue、RoutingKey和死信队列
3. 改造抢票接口为返回QUEUED
4. 实现请求状态查询接口
5. 实现消费者创建订单
6. 实现消费者幂等
7. 实现确认、重试、死信和补偿
8. 保留订单详情查询
```

## 2. 异步后的完整流程

```text
客户端抢票
  ↓
Controller
  ↓
Service执行Lua资格预扣
  ↓
Redis记录requestId状态=QUEUED
  ↓
发送RabbitMQ消息
  ↓
立即返回requestId

RabbitMQ消费者
  ↓
MySQL事务创建订单
  ↓
Redis状态改为SUCCESS或FAILED
  ↓
第10阶段通过WebSocket通知用户
```

## 3. 分层职责边界

| 层 | 职责 |
| --- | --- |
| 接口层 | 提交抢票请求、查询请求状态、查询订单详情 |
| 业务层 | Lua预扣、状态记录、消息发布、订单事务、补偿 |
| 数据访问层 | 原子更新MySQL库存、插入订单、按requestId查询 |
| 配置层 | 声明Exchange、Queue、Binding、消息转换器和确认策略 |
| 消息层 | Producer只发布消息；Consumer只接收并调用Service |

Consumer不要直接写复杂Mapper流程，应调用 `AsyncOrderService.createOrder(message)`。

## 4. 增加依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

## 5. 修改数据库

订单增加请求ID：

```sql
ALTER TABLE ticket_order
    ADD COLUMN request_id VARCHAR(40) NOT NULL COMMENT '抢票请求ID' AFTER order_no,
    ADD UNIQUE KEY uk_ticket_order_request_id (request_id);
```

`request_id` 用于消费者幂等，不能只依赖消息投递次数。

## 6. 消息拓扑

```text
Exchange: flash.ticket.order.exchange
RoutingKey: flash.ticket.order.create
Queue: flash.ticket.order.queue

Dead Letter Exchange: flash.ticket.order.dlx
Dead Letter RoutingKey: flash.ticket.order.dead
Dead Letter Queue: flash.ticket.order.dead.queue
```

配置层声明：

```text
DirectExchange
Queue
Binding
Jackson2JsonMessageConverter
```

名称集中定义在 `RabbitNames`，不要散落字符串。

## 7. 消息对象

`CreateOrderMessage`至少包含：

```text
requestId
userId
eventId
stockId
createdAt
```

消息中不要传完整Entity，也不要依赖Java默认序列化。

消息版本变化时应保持向后兼容，例如后续增加字段而不删除旧字段。

## 8. 请求状态

Redis Key：

```text
flash:ticket:request:{requestId}
```

状态：

```text
QUEUED       已入队等待处理
PROCESSING   消费者处理中
SUCCESS      订单创建成功
FAILED       创建失败
```

成功结果同时保存 `orderNo`，失败结果保存可展示原因。

设置合理TTL，例如24小时，避免状态Key永久增长。

## 9. 接口1：提交异步抢票请求

```http
POST /api/seckill/orders
```

请求不变：

```json
{
  "userId": 1,
  "stockId": 10
}
```

响应改为：

```json
{
  "requestId": "20261110...",
  "status": "QUEUED"
}
```

### Service流程

```text
1. 生成全局唯一requestId
2. 活动和用户基础校验
3. Lua预扣资格
4. Redis写requestId=QUEUED
5. 发布CreateOrderMessage
6. 等待Publisher Confirm结果
7. 发布失败则执行Redis补偿并把状态改为FAILED
8. 发布成功则返回QUEUED
```

不能在消息尚未确认时向客户端声称订单成功。

### 验收

- 接口快速返回，不等待MySQL订单创建。
- 返回的是QUEUED，不是SUCCESS。
- 消息发布失败会补偿资格和库存。

## 10. 接口2：查询请求状态

```http
GET /api/seckill/requests/{requestId}
```

响应示例：

```json
{
  "requestId": "...",
  "status": "SUCCESS",
  "orderNo": "...",
  "message": "抢票成功"
}
```

Service只从受控状态存储读取，不让Controller直接使用RedisTemplate。

### 验收

- 入队后返回QUEUED或PROCESSING。
- 消费成功后返回SUCCESS和orderNo。
- 失败后返回FAILED。
- 不存在或过期的requestId返回明确结果。

## 11. 消费者创建订单

Consumer职责保持很薄：

```java
@RabbitListener(queues = RabbitNames.ORDER_QUEUE)
public void consume(CreateOrderMessage message, Channel channel, Message rawMessage) {
    asyncOrderService.createOrder(message);
    // 根据结果ack或nack
}
```

`AsyncOrderService`事务流程：

```text
1. 检查requestId是否已存在订单
2. Redis状态改为PROCESSING
3. MySQL条件扣库存
4. 插入订单，保存requestId
5. 事务提交
6. Redis状态改为SUCCESS并保存orderNo
```

如果第7阶段已经在同步流程中扣过MySQL库存，异步化时必须调整为只由消费者扣一次，禁止入口和消费者都扣MySQL库存。

## 12. 消费者幂等

RabbitMQ可能重复投递，因此消费者必须支持同一消息多次处理。

保护层：

```text
第一层：按requestId查询订单，存在则直接视为成功
第二层：uk_ticket_order_request_id唯一索引
第三层：uk_ticket_order_user_stock唯一索引
```

如果重复消息命中已有订单：

- 不再次扣库存。
- 将请求状态修正为SUCCESS。
- 确认消息。

## 13. 消息确认与失败分类

### 可重试异常

```text
数据库暂时不可用
网络短暂中断
连接池暂时耗尽
```

可以有限重试，超过次数进入死信队列。

### 不可重试异常

```text
用户不存在
活动不存在
重复购买
数据格式错误
```

直接标记FAILED、执行必要补偿并确认消息，避免无限循环。

### 手动确认原则

```text
处理成功或确认不可重试：basicAck
暂时异常且允许再次投递：basicNack/requeue或框架重试
超过重试次数：进入DLQ
```

不要无限 `requeue=true`。

## 14. 补偿逻辑

如果消费者最终无法创建订单：

```text
1. Redis请求状态改为FAILED
2. 执行幂等Lua补偿
3. 恢复Redis库存并移除用户资格
4. MySQL事务如果已回滚，无需额外恢复MySQL库存
```

补偿也可能失败，因此死信队列消息和错误日志必须包含 `requestId`，便于人工或定时任务重试。

## 15. 接口3：订单详情

继续使用：

```http
GET /api/orders/{orderNo}
```

只有请求状态为SUCCESS并得到orderNo后，客户端才查询订单详情。

## 16. 测试顺序

```text
1. 单条消息正常创建订单
2. 同一消息重复投递不重复扣库存
3. Publisher失败执行入口补偿
4. Consumer暂时失败后重试成功
5. Consumer永久失败进入死信队列
6. 永久失败执行资格补偿
7. 1000个请求下接口快速返回
8. 成功订单数不超过库存
```

## 17. 本阶段的一致性说明

Publisher Confirm可以降低消息丢失风险，但“Redis预扣”和“消息发送”仍不是一个原子事务。

更高级的可靠方案包括：

```text
本地消息表/Outbox
事务消息
定时扫描QUEUED超时请求
Redis与MySQL定期对账
```

初学版本至少实现超时请求扫描和死信处理计划，不要声称完全不存在消息丢失窗口。

## 18. 阶段验收清单

- [ ] 抢票接口返回QUEUED而不是假成功。
- [ ] requestId全局唯一。
- [ ] Exchange、Queue、Binding和DLQ声明完整。
- [ ] Publisher失败执行补偿。
- [ ] Consumer事务创建订单。
- [ ] 重复消息不会重复扣库存。
- [ ] 可重试与不可重试异常分开处理。
- [ ] 请求状态最终为SUCCESS或FAILED。
- [ ] 死信消息可以定位到requestId。

## 19. 下一阶段

下一阶段建立WebSocket连接，在请求状态变为SUCCESS或FAILED后主动通知客户端，并补一个只展示核心流程的简单页面。

## 20. 官方参考

- Spring Boot RabbitMQ：`https://docs.spring.io/spring-boot/3.5/reference/messaging/amqp.html`
- Spring AMQP Reference：`https://docs.spring.io/spring-amqp/reference/`
- RabbitMQ Reliability：`https://www.rabbitmq.com/docs/reliability`

## 21. 完整代码答案

本阶段把HTTP请求和MySQL创建订单拆开：接口线程只完成Redis预扣、记录请求和投递消息；消费者再创建订单。客户端拿到 `requestId` 后查询最终状态。

### 21.1 依赖与配置

`pom.xml` 的 `<dependencies>` 新增：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

在 `application.yml` 的 `spring` 下新增：

```yaml
  rabbitmq:
    host: ${RABBIT_HOST:localhost}
    port: ${RABBIT_PORT:5672}
    username: ${RABBIT_USERNAME:guest}
    password: ${RABBIT_PASSWORD:guest}
    publisher-confirm-type: correlated
    listener:
      simple:
        acknowledge-mode: auto
        prefetch: 20
```

`sql/02-seckill-request.sql`

```sql
USE flash_ticket;
CREATE TABLE IF NOT EXISTS seckill_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(40) NOT NULL,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    status TINYINT NOT NULL COMMENT '0待处理 1成功 2失败 3处理中',
    order_id BIGINT NULL,
    failure_reason VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_request_user (user_id)
) ENGINE=InnoDB;
```

### 21.2 RabbitMQ配置层

`src/main/java/com/example/flashticket/config/RabbitConfig.java`

```java
package com.example.flashticket.config;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
@Configuration
public class RabbitConfig {
    public static final String EXCHANGE="ft.seckill.exchange";
    public static final String QUEUE="ft.seckill.order.queue";
    public static final String ROUTING_KEY="ft.seckill.order";
    @Bean DirectExchange seckillExchange(){return new DirectExchange(EXCHANGE,true,false);}
    @Bean Queue seckillQueue(){return QueueBuilder.durable(QUEUE).build();}
    @Bean Binding seckillBinding(Queue seckillQueue,DirectExchange seckillExchange){
        return BindingBuilder.bind(seckillQueue).to(seckillExchange).with(ROUTING_KEY);
    }
}
```

### 21.3 数据访问层

`src/main/java/com/example/flashticket/entity/SeckillRequestEntity.java`

```java
package com.example.flashticket.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;
@Data @TableName("seckill_request")
public class SeckillRequestEntity {
    @TableId(type=IdType.AUTO) private Long id;
    private String requestId; private Long userId; private Long stockId; private Integer status;
    private Long orderId; private String failureReason;
    private LocalDateTime createdAt; private LocalDateTime updatedAt;
}
```

`src/main/java/com/example/flashticket/mapper/SeckillRequestMapper.java`

```java
package com.example.flashticket.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashticket.entity.SeckillRequestEntity;
import org.apache.ibatis.annotations.*;
@Mapper
public interface SeckillRequestMapper extends BaseMapper<SeckillRequestEntity> {
    @Update("UPDATE seckill_request SET status=3 WHERE request_id=#{requestId} AND status=0")
    int claim(@Param("requestId")String requestId);
    @Update("UPDATE seckill_request SET status=1,order_id=#{orderId},failure_reason=NULL WHERE request_id=#{requestId}")
    int success(@Param("requestId")String requestId,@Param("orderId")Long orderId);
    @Update("UPDATE seckill_request SET status=2,failure_reason=#{reason} WHERE request_id=#{requestId}")
    int fail(@Param("requestId")String requestId,@Param("reason")String reason);
}
```

### 21.4 消息、DTO和VO

`src/main/java/com/example/flashticket/messaging/SeckillMessage.java`

```java
package com.example.flashticket.messaging;
import java.io.Serializable;
public record SeckillMessage(String requestId,Long userId,Long stockId) implements Serializable {}
```

`src/main/java/com/example/flashticket/vo/SeckillRequestVo.java`

```java
package com.example.flashticket.vo;
public record SeckillRequestVo(String requestId,Long userId,Long stockId,Integer status,Long orderId,String failureReason) {}
```

### 21.5 生产者和异步业务层

`src/main/java/com/example/flashticket/messaging/SeckillProducer.java`

```java
package com.example.flashticket.messaging;
import com.example.flashticket.config.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
@Component
public class SeckillProducer {
    private final RabbitTemplate rabbit;
    public SeckillProducer(RabbitTemplate rabbit){this.rabbit=rabbit;}
    public void send(SeckillMessage message){rabbit.convertAndSend(RabbitConfig.EXCHANGE,RabbitConfig.ROUTING_KEY,message);}
}
```

`src/main/java/com/example/flashticket/service/AsyncSeckillService.java`

```java
package com.example.flashticket.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashticket.config.LockKeys;
import com.example.flashticket.dto.SeckillRequest;
import com.example.flashticket.entity.SeckillRequestEntity;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.mapper.SeckillRequestMapper;
import com.example.flashticket.messaging.*;
import com.example.flashticket.vo.SeckillRequestVo;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AsyncSeckillService {
    private final RedisReservationService reservation; private final SeckillRequestMapper mapper;
    private final SeckillProducer producer; private final DistributedLockExecutor locks;
    public AsyncSeckillService(RedisReservationService reservation,SeckillRequestMapper mapper,
            SeckillProducer producer,DistributedLockExecutor locks){
        this.reservation=reservation;this.mapper=mapper;this.producer=producer;this.locks=locks;
    }
    @Transactional
    public SeckillRequestVo submit(SeckillRequest r){
        return locks.execute(LockKeys.seckill(r.userId(),r.stockId()),()->submitLocked(r));
    }
    private SeckillRequestVo submitLocked(SeckillRequest r){
        long result=reservation.reserve(r.stockId(),r.userId());
        if(result==1) throw new BusinessException(409,"不能重复抢同一票档");
        if(result==2) throw new BusinessException(409,"库存不足");
        String requestId=UUID.randomUUID().toString().replace("-","");
        try {
            SeckillRequestEntity e=new SeckillRequestEntity();
            e.setRequestId(requestId);e.setUserId(r.userId());e.setStockId(r.stockId());e.setStatus(0);
            mapper.insert(e); producer.send(new SeckillMessage(requestId,r.userId(),r.stockId()));
            return toVo(e);
        } catch(RuntimeException e) {
            reservation.rollback(r.stockId(),r.userId()); throw e;
        }
    }
    public SeckillRequestVo get(String requestId){
        SeckillRequestEntity e=mapper.selectOne(new LambdaQueryWrapper<SeckillRequestEntity>()
                .eq(SeckillRequestEntity::getRequestId,requestId));
        if(e==null) throw new BusinessException(404,"抢票请求不存在");
        return toVo(e);
    }
    private SeckillRequestVo toVo(SeckillRequestEntity e){
        return new SeckillRequestVo(e.getRequestId(),e.getUserId(),e.getStockId(),e.getStatus(),e.getOrderId(),e.getFailureReason());
    }
}
```

### 21.6 消费者

`src/main/java/com/example/flashticket/messaging/SeckillConsumer.java`

```java
package com.example.flashticket.messaging;
import com.example.flashticket.config.RabbitConfig;
import com.example.flashticket.dto.OrderDtos.CreateOrderRequest;
import com.example.flashticket.mapper.SeckillRequestMapper;
import com.example.flashticket.service.*;
import com.example.flashticket.vo.OrderVos.OrderVo;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
@Component
public class SeckillConsumer {
    private final SeckillRequestMapper requests; private final TicketOrderService orders;
    private final RedisReservationService reservation;
    public SeckillConsumer(SeckillRequestMapper requests,TicketOrderService orders,RedisReservationService reservation){
        this.requests=requests;this.orders=orders;this.reservation=reservation;
    }
    @RabbitListener(queues=RabbitConfig.QUEUE)
    public void consume(SeckillMessage m){
        if(requests.claim(m.requestId())!=1) return; // 重复消息无法再次取得处理权
        try {
            OrderVo order=orders.create(new CreateOrderRequest(m.userId(),m.stockId()));
            requests.success(m.requestId(),order.id());
        } catch(RuntimeException e) {
            reservation.rollback(m.stockId(),m.userId());
            String reason=e.getMessage()==null?"订单创建失败":e.getMessage();
            requests.fail(m.requestId(),reason.substring(0,Math.min(reason.length(),255)));
        }
    }
}
```

### 21.7 接口层

`src/main/java/com/example/flashticket/controller/AsyncSeckillController.java`

```java
package com.example.flashticket.controller;

import com.example.flashticket.dto.SeckillRequest;
import com.example.flashticket.service.AsyncSeckillService;
import com.example.flashticket.vo.SeckillRequestVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seckill/requests")
public class AsyncSeckillController {
    private final AsyncSeckillService service;

    public AsyncSeckillController(AsyncSeckillService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<SeckillRequestVo> submit(@Valid @RequestBody SeckillRequest r) {
        return ApiResponse.success(service.submit(r));
    }

    @GetMapping("/{requestId}")
    public ApiResponse<SeckillRequestVo> get(@PathVariable String requestId) {
        return ApiResponse.success(service.get(requestId));
    }
}
```

### 21.8 验证顺序

执行建表脚本并启动RabbitMQ。提交一次请求应先返回状态0，随后查询变为1或2。重复投递同一消息时 `claim` 返回0，不能生成第二张订单。这里是教学版：生产系统还应使用Outbox或可靠发布机制处理“数据库已提交但消息未真正到达”的窗口。

