# 第8阶段：Redisson分布式锁

## 1. 阶段目标

本阶段学习在多个Spring Boot实例之间进行互斥控制，但不使用分布式锁替代Lua、数据库事务或唯一索引。

实现顺序：

```text
1. 接入Redisson
2. 为同一用户同一票档的订单处理加细粒度锁
3. 为取消与补偿流程加细粒度锁
4. 为活动预热任务加锁
5. 验证锁超时、异常释放和多实例互斥
```

## 2. 不同机制的边界

| 机制 | 解决什么问题 |
| --- | --- |
| Redis Lua | 多个Redis命令的原子执行 |
| Redisson锁 | 多个应用实例对同一业务资源互斥 |
| MySQL事务 | 数据库内多条SQL同时提交或回滚 |
| 唯一索引 | 数据库最终拒绝重复数据 |

错误做法：

```text
给所有用户的抢票请求使用同一个全局锁
认为加锁后可以删除数据库唯一索引
认为加锁后可以不做事务
锁内执行长时间HTTP调用
```

## 3. 分层职责边界

| 层 | 职责 |
| --- | --- |
| 接口层 | 不知道锁的存在，接口契约保持不变 |
| 业务层 | 决定锁住哪个业务资源、锁内执行哪些最小操作 |
| 数据访问层 | 不获取分布式锁，只执行SQL |
| 配置层 | 创建RedissonClient并读取连接配置 |
| Redis | 保存锁状态，不保存Java线程对象 |

调用关系：

```text
Controller
  ↓
Service获取业务锁
  ↓
try内执行Lua/事务
  ↓
finally释放锁
```

## 4. 增加依赖

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.52.0</version>
</dependency>
```

本文使用Redisson核心包并在配置层显式创建 `RedissonClient`，这样单节点与Cluster配置的来源更容易看懂。不要再同时加入Starter，以免出现两个客户端Bean。

## 5. 配置层

单节点开发配置示例：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

redisson:
  single-server-config:
    address: redis://127.0.0.1:6379
```

实际属性名称以所用Starter版本为准。配置层最终只提供：

```java
RedissonClient
```

业务Key不能硬编码在配置类中。

## 6. 锁Key设计

```text
flash:ticket:lock:order:{stockId}:{userId}
flash:ticket:lock:cancel:{orderNo}
flash:ticket:lock:warm-up:{eventId}
```

锁范围越大，并发度越低。

例如：

```text
lock:order:10:1001 只阻塞用户1001对票档10的重复处理
lock:order:10      会阻塞所有用户抢票档10，不推荐
lock:order         会把系统退化为单线程，错误
```

## 7. 改造接口1：同用户抢票互斥

接口保持：

```http
POST /api/seckill/orders
```

Service流程：

```text
1. 生成 userId + stockId 锁Key
2. 尝试在有限时间内获取锁
3. 获取失败返回“请求处理中，请勿重复提交”
4. 获取成功后执行Lua资格判断与订单流程
5. finally中确认当前线程持有锁后释放
```

示例结构：

```java
RLock lock = redissonClient.getLock(lockKey);
boolean locked = false;
try {
    locked = lock.tryLock(200, 10, TimeUnit.MILLISECONDS);
    if (!locked) {
        throw new BusinessException("请求正在处理中，请勿重复提交");
    }
    return doSeckill(request);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new BusinessException("请求被中断");
} finally {
    if (locked && lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

注意：示例中的时间参数必须根据实际方法签名和业务耗时调整。锁租期不能短于正常业务执行时间。

### 验收

- 同一用户连续请求时只有一个进入核心流程。
- 不同用户仍可并发。
- 业务异常后锁被释放。
- 线程被中断时保留中断状态。

## 8. 改造接口2：取消订单互斥

```http
POST /api/orders/{orderNo}/cancel
```

以 `orderNo` 为锁粒度：

```text
获取订单锁
→ 执行MySQL条件状态更新
→ 恢复MySQL库存
→ 执行Redis资格和库存补偿
→ 释放锁
```

即使有锁，仍保留MySQL：

```sql
WHERE status = 'PENDING'
```

原因：锁可能超时、Redis可能故障、应用可能崩溃，数据库条件更新才是最终保护。

### 验收

- 两个应用实例同时取消同一订单，只恢复一次库存。
- 锁异常时数据库条件更新仍阻止重复恢复。

## 9. 接口3：活动预热互斥

```http
POST /api/admin/cache/events/{eventId}/warm-up
```

多个实例可能同时收到预热请求。以 `eventId` 加锁：

```text
获取活动预热锁
→ 再次检查缓存是否已经存在
→ 从MySQL加载
→ 写活动、票档和库存Key
→ 释放锁
```

锁内必须再次检查缓存，因为等待锁期间其他实例可能已经完成预热。

## 10. 锁的异常场景

### 未获取锁却解锁

必须使用：

```java
if (locked && lock.isHeldByCurrentThread()) {
    lock.unlock();
}
```

### 锁租期过短

业务未执行完锁已过期，另一个实例会进入。解决方式：

- 缩小锁内代码。
- 合理配置租期。
- 了解Redisson看门狗机制适用的API。
- 数据库仍保留幂等保护。

### Redis故障

分布式锁不能取得时，高风险写操作应快速失败，而不是无锁继续执行。

## 11. 多实例测试

分别启动：

```powershell
java -jar target\flash-ticket.jar --server.port=8080
java -jar target\flash-ticket.jar --server.port=8081
```

测试顺序：

```text
1. 两实例处理不同用户请求
2. 两实例处理同用户同票档请求
3. 两实例取消同一订单
4. 两实例预热同一活动
5. 锁内主动抛异常，确认锁释放
```

## 12. 阶段验收清单

- [ ] 锁Key具有明确业务粒度。
- [ ] 没有全局抢票锁。
- [ ] 所有解锁都在finally中。
- [ ] 解锁前检查当前线程持锁。
- [ ] 数据库唯一索引和条件更新仍保留。
- [ ] 不同用户可以并行。
- [ ] 两个应用实例测试通过。

## 13. 下一阶段

下一阶段使用RabbitMQ把MySQL订单创建移出抢票请求线程，实现削峰。分布式锁继续用于同资源互斥，但消费者幂等主要依赖请求ID和数据库唯一索引。

## 14. 官方参考

- Redisson Spring集成：`https://redisson.pro/docs/integration-with-spring/`
- Redisson Locks：`https://redisson.pro/docs/data-and-services/locks-and-synchronizers/`

## 15. 完整代码答案

沿用第7阶段代码。锁只包围同一用户同一票档、同一订单取消、同一票档预热，不锁整个系统。Lua和MySQL约束继续保留。

### 15.1 Maven依赖

在 `<dependencies>` 中新增：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.52.0</version>
</dependency>
```

### 15.2 配置层

`src/main/java/com/example/flashticket/config/RedissonConfig.java`

```java
package com.example.flashticket.config;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration
public class RedissonConfig {
    @Bean(destroyMethod="shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {
        Config config=new Config();
        var server=config.useSingleServer().setAddress("redis://"+host+":"+port);
        if(password!=null&&!password.isBlank()) server.setPassword(password);
        return Redisson.create(config);
    }
}
```

`src/main/java/com/example/flashticket/config/LockKeys.java`

```java
package com.example.flashticket.config;
public final class LockKeys {
    private LockKeys() {}
    public static String seckill(Long userId,Long stockId){return "ft:lock:seckill:"+userId+":"+stockId;}
    public static String order(Long orderId){return "ft:lock:order:"+orderId;}
    public static String warm(Long stockId){return "ft:lock:warm:"+stockId;}
}
```

### 15.3 通用锁执行器

`src/main/java/com/example/flashticket/service/DistributedLockExecutor.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.exception.BusinessException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.*;
import org.springframework.stereotype.Service;
@Service
public class DistributedLockExecutor {
    private final RedissonClient redisson;
    public DistributedLockExecutor(RedissonClient redisson){this.redisson=redisson;}
    public <T> T execute(String key,Supplier<T> action){
        RLock lock=redisson.getLock(key); boolean acquired=false;
        try {
            acquired=lock.tryLock(2,TimeUnit.SECONDS); // 获取后由watchdog自动续期
            if(!acquired) throw new BusinessException(429,"操作正在处理中，请稍后重试");
            return action.get();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503,"线程被中断，请重试");
        } finally {
            if(acquired&&lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
```

### 15.4 完整替换抢票业务

`src/main/java/com/example/flashticket/service/SeckillService.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.config.LockKeys;
import com.example.flashticket.dto.*;
import com.example.flashticket.entity.TicketStock;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.vo.OrderVos.OrderVo;
import org.springframework.stereotype.Service;
@Service
public class SeckillService {
    private final RedisReservationService reservation; private final TicketStockService stocks;
    private final TicketOrderService orders; private final DistributedLockExecutor locks;
    public SeckillService(RedisReservationService reservation,TicketStockService stocks,
            TicketOrderService orders,DistributedLockExecutor locks){
        this.reservation=reservation;this.stocks=stocks;this.orders=orders;this.locks=locks;
    }
    public void warm(Long stockId){
        locks.execute(LockKeys.warm(stockId),()->{
            TicketStock stock=stocks.requireStock(stockId);
            reservation.warm(stockId,stock.getAvailableStock()); return null;
        });
    }
    public OrderVo seckill(SeckillRequest r){
        return locks.execute(LockKeys.seckill(r.userId(),r.stockId()),()->{
            long result=reservation.reserve(r.stockId(),r.userId());
            if(result==1) throw new BusinessException(409,"不能重复抢同一票档");
            if(result==2) throw new BusinessException(409,"库存不足");
            try { return orders.create(new OrderDtos.CreateOrderRequest(r.userId(),r.stockId())); }
            catch(RuntimeException e){reservation.rollback(r.stockId(),r.userId());throw e;}
        });
    }
}
```

### 15.5 取消订单门面与Controller替换

`src/main/java/com/example/flashticket/service/OrderCommandFacade.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.config.LockKeys;
import com.example.flashticket.vo.OrderVos.OrderVo;
import org.springframework.stereotype.Service;
@Service
public class OrderCommandFacade {
    private final TicketOrderService orders; private final DistributedLockExecutor locks;
    public OrderCommandFacade(TicketOrderService orders,DistributedLockExecutor locks){this.orders=orders;this.locks=locks;}
    public OrderVo cancel(Long orderId){return locks.execute(LockKeys.order(orderId),()->orders.cancel(orderId));}
}
```

`src/main/java/com/example/flashticket/controller/TicketOrderController.java`

```java
package com.example.flashticket.controller;

import com.example.flashticket.dto.OrderDtos.CreateOrderRequest;
import com.example.flashticket.vo.OrderVos.OrderVo;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class TicketOrderController {
    private final TicketOrderService orders;
    private final OrderCommandFacade commands;

    public TicketOrderController(TicketOrderService orders, OrderCommandFacade commands) {
        this.orders = orders;
        this.commands = commands;
    }

    @PostMapping
    public ApiResponse<OrderVo> create(@Valid @RequestBody CreateOrderRequest r) {
        return ApiResponse.success(orders.create(r));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderVo> get(@PathVariable Long id) {
        return ApiResponse.success(orders.get(id));
    }

    @GetMapping
    public ApiResponse<List<OrderVo>> list(@RequestParam Long userId) {
        return ApiResponse.success(orders.listByUser(userId));
    }

    @PostMapping("/{id}/pay")
    public ApiResponse<OrderVo> pay(@PathVariable Long id) {
        return ApiResponse.success(orders.pay(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<OrderVo> cancel(@PathVariable Long id) {
        return ApiResponse.success(commands.cancel(id));
    }
}
```

### 验证顺序

先单实例验证锁一定在 `finally` 释放；再启动8080和8081两个实例，同时用相同用户抢同一票档，只有一个线程进入业务区。随后用不同用户并发，确认它们使用不同锁Key，可以并行执行。

