# 第7阶段：Redis Lua原子抢票

## 1. 阶段目标

本阶段把抢票入口从MySQL条件扣库存升级为Redis Lua原子判断，再同步创建MySQL订单。

实现顺序：

```text
1. 设计同槽位Redis Key
2. 编写抢票Lua脚本
3. 实现抢票接口
4. 同步创建MySQL订单
5. 编写失败补偿Lua脚本
6. 查询抢票结果
7. 并发验证
```

RabbitMQ会在第9阶段加入。本阶段先保持同步流程，便于理解Lua解决了什么问题。

## 2. Lua解决的问题

普通Redis命令存在竞态：

```text
检查用户是否抢过
检查库存
扣减库存
记录用户已抢
```

如果分成四次网络请求，多个线程可能在命令之间交叉执行。

Redis执行Lua脚本时，会把脚本内多个命令作为一个不可被其他命令插入的整体执行。

## 3. 分层职责边界

| 层 | 职责 |
| --- | --- |
| 接口层 | 接收用户和票档请求，返回订单或明确失败结果 |
| 业务层 | 校验活动时间、执行Lua、调用订单事务、失败补偿 |
| 数据访问层 | MySQL订单与最终库存写入，不执行Redis脚本 |
| 配置层 | 注册RedisScript Bean，不写抢票流程 |
| Lua资源 | 只处理Redis内的资格和库存原子操作 |

调用关系：

```text
SeckillController
  ↓
SeckillService
  ├─ 活动基础校验
  ├─ Redis Lua资格预扣
  ├─ TicketOrderService创建MySQL订单
  └─ 失败时执行补偿Lua
```

## 4. Key设计

为后续Redis Cluster预留Hash Tag：

```text
flash:ticket:seckill:{stockId}:stock
flash:ticket:seckill:{stockId}:users
```

花括号中的 `{stockId}` 是Redis Cluster Hash Tag。两个Key包含相同Hash Tag时会落在同一个槽位，Lua脚本才能同时操作它们。

不要使用：

```text
flash:ticket:stock:10
flash:ticket:users:10
```

它们在集群中可能落入不同槽位，导致 `CROSSSLOT`。

## 5. 抢票Lua脚本

文件：

```text
src/main/resources/lua/seckill.lua
```

```lua
local stockKey = KEYS[1]
local usersKey = KEYS[2]
local userId = ARGV[1]

local stock = redis.call('GET', stockKey)
if not stock then
    return 3
end

if redis.call('SISMEMBER', usersKey, userId) == 1 then
    return 2
end

if tonumber(stock) <= 0 then
    return 1
end

redis.call('DECR', stockKey)
redis.call('SADD', usersKey, userId)
return 0
```

返回码约定：

| 返回值 | 含义 |
| ---: | --- |
| 0 | 获取抢票资格成功 |
| 1 | 库存不足 |
| 2 | 用户重复抢票 |
| 3 | 活动库存尚未预热 |

Lua脚本不创建MySQL订单，也不判断用户是否被禁用，这些属于业务层和数据库职责。

## 6. 配置层注册脚本

```java
@Configuration
public class RedisScriptConfig {

    @Bean
    public DefaultRedisScript<Long> seckillScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
```

配置层只负责加载脚本，不能注入Mapper或创建订单。

## 7. 接口1：抢票下单

```http
POST /api/seckill/orders
```

```json
{
  "userId": 1,
  "stockId": 10
}
```

### Controller职责

- 校验ID非空且为正数。
- 调用 `SeckillService.seckill()`。
- 返回成功订单VO或统一业务错误。

### Service完整流程

```text
1. 查询活动和票档基础信息
2. 校验活动为ON_SALE
3. 校验当前时间在售票区间
4. 构造同槽位Redis Key
5. 执行seckill.lua
6. 根据返回码转换业务结果
7. 返回0时调用订单事务创建MySQL订单
8. MySQL创建失败时执行Redis补偿
9. 返回订单VO
```

### Lua执行示例

```java
Long result = redisTemplate.execute(
        seckillScript,
        List.of(stockKey, usersKey),
        userId.toString()
);
```

必须处理 `result == null`，它通常表示Redis执行异常，不应当视为成功。

### MySQL订单事务

虽然Redis已经预扣库存，MySQL仍需：

```text
条件扣减数据库库存
插入订单
唯一索引防重复
事务回滚
```

Redis是高并发入口，MySQL仍是最终事实来源。

### 接口验收

- 第一个请求成功。
- 同一用户再次请求返回重复抢票。
- Redis库存为0后返回售罄。
- Redis库存不出现负数。
- MySQL订单与库存同步变化。

## 8. 补偿Lua脚本

如果Redis预扣成功，但MySQL订单创建失败，需要撤销资格。

文件：

```text
src/main/resources/lua/seckill-compensate.lua
```

```lua
local stockKey = KEYS[1]
local usersKey = KEYS[2]
local userId = ARGV[1]

if redis.call('SREM', usersKey, userId) == 1 then
    redis.call('INCR', stockKey)
    return 1
end

return 0
```

先 `SREM`，只有成功移除资格才增加库存，避免重复补偿导致库存增加多次。

## 9. 接口2：查询抢票结果

同步阶段可以继续使用：

```http
GET /api/orders/{orderNo}
```

抢票接口成功时直接返回订单号，调用方再查询订单详情。

第9阶段异步化后会新增按 `requestId` 查询处理状态的接口。

## 10. 异常边界

### Redis失败

Lua未执行完成时直接返回系统繁忙，不要绕过Redis直接打MySQL，否则突发流量会压垮数据库。

### MySQL失败

执行补偿Lua。如果补偿也失败：

- 记录错误日志和用户、票档、请求信息。
- 将补偿任务写入可靠的重试机制。
- 不向客户端伪装成成功。

本阶段先记录待补偿问题，第9阶段通过消息与对账进一步完善。

## 11. 并发测试顺序

### 测试1：100用户抢10张

断言：

```text
Redis库存=0
Redis users集合大小=10
MySQL订单数=10
MySQL库存=0
```

### 测试2：同一用户并发20次

断言：

```text
成功资格=1
MySQL订单=1
Redis库存只减少1
```

### 测试3：强制MySQL失败

断言补偿后：

```text
Redis库存恢复
用户从users集合移除
MySQL没有订单
```

## 12. 本阶段的已知限制

以下窗口仍然存在：

```text
Lua成功后应用崩溃，尚未创建MySQL订单
MySQL失败后应用崩溃，尚未执行补偿
```

因此本阶段是学习原子抢票，不是最终分布式一致性方案。第9阶段会加入消息队列和请求状态，第12阶段增加对账监控。

## 13. 阶段验收清单

- [ ] 两个Lua Key使用同一Hash Tag。
- [ ] Lua同时处理重复用户和库存。
- [ ] 返回码被明确转换为业务错误。
- [ ] Redis预扣后MySQL仍有最终保护。
- [ ] MySQL失败时执行幂等补偿。
- [ ] 并发测试不超卖、不重复。
- [ ] 已记录崩溃窗口，不把方案描述成绝对一致。

## 14. 下一阶段

下一阶段引入Redisson，只在确实需要跨实例互斥的场景加锁，不给整个抢票库存加一把大锁。

## 15. 官方参考

- Redis Lua脚本：`https://redis.io/docs/latest/develop/programmability/eval-intro/`
- Redis Cluster Key Hashing：`https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/`

## 16. 完整代码答案

本阶段沿用第6阶段依赖。先写Lua脚本，再写配置和Redis数据访问服务，最后开放抢票接口。Lua只负责Redis资格预扣；MySQL订单仍由第5阶段的事务和唯一索引最终确认。

### 文件1：`src/main/resources/lua/seckill-reserve.lua`

```lua
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 1
end
if stock <= 0 then
    return 2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
```

### 文件2：`src/main/resources/lua/seckill-rollback.lua`

```lua
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end
return 0
```

### 文件3：`src/main/java/com/example/flashticket/config/SeckillKeys.java`

花括号是Redis Cluster的hash tag，保证两个Key进入同一slot，Lua才能同时操作。

```java
package com.example.flashticket.config;
public final class SeckillKeys {
    private SeckillKeys() {}
    public static String stock(Long stockId){return "ft:seckill:{"+stockId+"}:stock";}
    public static String users(Long stockId){return "ft:seckill:{"+stockId+"}:users";}
}
```

### 文件4：`src/main/java/com/example/flashticket/config/LuaScriptConfig.java`

```java
package com.example.flashticket.config;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
@Configuration
public class LuaScriptConfig {
    @Bean("reserveScript")
    public DefaultRedisScript<Long> reserveScript(){
        return new DefaultRedisScript<>(new ClassPathResource("lua/seckill-reserve.lua"),Long.class);
    }
    @Bean("rollbackScript")
    public DefaultRedisScript<Long> rollbackScript(){
        return new DefaultRedisScript<>(new ClassPathResource("lua/seckill-rollback.lua"),Long.class);
    }
}
```

### 文件5：`src/main/java/com/example/flashticket/service/RedisReservationService.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.config.SeckillKeys;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
@Service
public class RedisReservationService {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> rollbackScript;
    public RedisReservationService(StringRedisTemplate redis,
            @Qualifier("reserveScript") DefaultRedisScript<Long> reserveScript,
            @Qualifier("rollbackScript") DefaultRedisScript<Long> rollbackScript){
        this.redis=redis;this.reserveScript=reserveScript;this.rollbackScript=rollbackScript;
    }
    public long reserve(Long stockId,Long userId){
        Long result=redis.execute(reserveScript,List.of(SeckillKeys.stock(stockId),SeckillKeys.users(stockId)),userId.toString());
        if(result==null) throw new IllegalStateException("Redis脚本没有返回结果");
        return result;
    }
    public void rollback(Long stockId,Long userId){
        redis.execute(rollbackScript,List.of(SeckillKeys.stock(stockId),SeckillKeys.users(stockId)),userId.toString());
    }
    public void warm(Long stockId,int available){
        redis.opsForValue().set(SeckillKeys.stock(stockId),Integer.toString(available));
        redis.delete(SeckillKeys.users(stockId));
    }
}
```

### 文件6：`src/main/java/com/example/flashticket/dto/SeckillRequest.java`

```java
package com.example.flashticket.dto;
import jakarta.validation.constraints.*;
public record SeckillRequest(@NotNull @Positive Long userId,@NotNull @Positive Long stockId) {}
```

### 文件7：`src/main/java/com/example/flashticket/service/SeckillService.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.dto.*;
import com.example.flashticket.entity.TicketStock;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.vo.OrderVos.OrderVo;
import org.springframework.stereotype.Service;
@Service
public class SeckillService {
    private final RedisReservationService reservation;
    private final TicketStockService stocks;
    private final TicketOrderService orders;
    public SeckillService(RedisReservationService reservation,TicketStockService stocks,TicketOrderService orders){
        this.reservation=reservation;this.stocks=stocks;this.orders=orders;
    }
    public void warm(Long stockId){
        TicketStock stock=stocks.requireStock(stockId);
        reservation.warm(stockId,stock.getAvailableStock());
    }
    public OrderVo seckill(SeckillRequest r){
        long result=reservation.reserve(r.stockId(),r.userId());
        if(result==1) throw new BusinessException(409,"不能重复抢同一票档");
        if(result==2) throw new BusinessException(409,"库存不足");
        try {
            return orders.create(new OrderDtos.CreateOrderRequest(r.userId(),r.stockId()));
        } catch (RuntimeException e) {
            reservation.rollback(r.stockId(),r.userId());
            throw e;
        }
    }
}
```

### 文件8：`src/main/java/com/example/flashticket/controller/SeckillController.java`

```java
package com.example.flashticket.controller;

import com.example.flashticket.dto.SeckillRequest;
import com.example.flashticket.service.SeckillService;
import com.example.flashticket.vo.OrderVos.OrderVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seckill")
public class SeckillController {
    private final SeckillService service;

    public SeckillController(SeckillService service) {
        this.service = service;
    }

    @PostMapping("/stocks/{stockId}/warm")
    public ApiResponse<Void> warm(@PathVariable Long stockId) {
        service.warm(stockId);
        return ApiResponse.success();
    }

    @PostMapping
    public ApiResponse<OrderVo> seckill(@Valid @RequestBody SeckillRequest r) {
        return ApiResponse.success(service.seckill(r));
    }
}
```

### 验证顺序

先预热指定票档，再调用抢票接口。依次验证：单用户成功、同用户重复失败、库存归零失败、故意让MySQL下单失败后Redis库存恢复。只有这4项都通过，才进行并发测试。

