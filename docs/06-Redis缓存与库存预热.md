# 第6阶段：Redis缓存与库存预热

## 1. 阶段目标

本阶段先使用单节点Redis实现活动缓存和库存预热，不立即使用Lua扣库存。

实现顺序：

```text
1. 缓存活动详情查询
2. 缓存活动票档查询
3. 修改活动后删除缓存
4. 修改票档后删除缓存
5. 管理端手动预热活动
6. 管理端清理活动缓存
7. 查询Redis展示库存
```

## 2. Redis和MySQL的职责

```text
MySQL：最终真实数据
Redis：查询加速和高并发入口数据
```

Redis中数据丢失后，系统必须能够从MySQL恢复。

本阶段不能删除第5阶段的MySQL条件扣库存和唯一索引。

## 3. 分层职责边界

| 层 | 职责 |
| --- | --- |
| 接口层 | 复用现有查询接口；管理端接口触发预热和清理 |
| 业务层 | 实现Cache-Aside流程、缓存失效与预热编排 |
| 数据访问层 | Mapper仍只访问MySQL，不访问Redis |
| 配置层 | 创建RedisTemplate及序列化配置 |
| 缓存组件 | 封装Key和基础缓存操作，不判断活动业务状态 |

调用关系：

```text
Controller
  ↓
TicketEventService
  ├─ Redis命中 → 返回
  └─ Redis未命中 → Mapper查询MySQL → 写Redis → 返回
```

Mapper不能注入RedisTemplate。

## 4. 增加依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Spring Boot默认使用Lettuce客户端。

## 5. Redis配置

`application-local.yml`：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      timeout: 2s
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 1s
```

如果使用连接池，需要确认依赖中存在Commons Pool支持；不要盲目增大连接数。

## 6. 配置层：Redis序列化

创建 `RedisConfig`：

```text
Key：StringRedisSerializer
Value：JSON序列化器
Hash Key：StringRedisSerializer
Hash Value：JSON序列化器
```

配置层只负责序列化和Bean，不定义：

```text
活动缓存Key
库存Key
缓存有效期业务规则
```

这些规则放到缓存组件或业务常量中。

## 7. Key设计

```text
flash:ticket:event:{eventId}
flash:ticket:event:{eventId}:stocks
flash:ticket:stock:{stockId}
flash:ticket:empty:event:{eventId}
```

Key必须包含业务前缀，避免与其他应用冲突。

建议创建 `RedisKeys`：

```java
public final class RedisKeys {
    public static String event(Long eventId) { ... }
    public static String eventStocks(Long eventId) { ... }
    public static String stock(Long stockId) { ... }
}
```

## 8. 接口1：缓存活动详情

复用：

```http
GET /api/events/{id}
```

使用Cache-Aside模式：

```text
1. 根据eventId生成Key
2. 查询Redis
3. 命中则直接返回VO
4. 未命中则查询MySQL
5. MySQL不存在则缓存短期空标记
6. MySQL存在则写Redis并设置TTL
7. 返回VO
```

建议TTL：

```text
30分钟 + 0至5分钟随机值
```

随机值避免大量Key同一时间过期造成缓存雪崩。

### 缓存穿透

不存在的ID如果每次都查MySQL，会形成穿透。可以缓存短期空标记：

```text
TTL 1至2分钟
```

### 验收

- 第一次请求出现MySQL查询。
- 第二次请求不再查询MySQL。
- 不存在ID短时间内不会反复访问MySQL。

## 9. 接口2：缓存活动票档

复用：

```http
GET /api/events/{eventId}/stocks
```

流程与活动详情相同，但Key和VO类型不同。

注意：

- 不要把Entity直接序列化为缓存长期协议。
- 缓存 `TicketStockVO` 或专用缓存对象。
- 展示库存可以缓存，但真正下单仍由MySQL判断。

### 验收

- 首次查询加载MySQL。
- 后续查询命中Redis。
- 不同活动的票档Key互不影响。

## 10. 接口3：修改活动后删除缓存

复用：

```http
PUT /api/events/{id}
PATCH /api/events/{id}/status
```

采用“先更新数据库，再删除缓存”：

```text
1. Service事务更新MySQL
2. 事务成功提交
3. 删除活动详情Key
4. 必要时删除票档列表Key
```

初学版可以在更新成功后直接删除。更严格的提交后删除可使用事务同步回调，避免事务最终回滚但缓存已被删除或重建。

不要直接修改缓存中的部分字段，删除缓存更简单可靠。

### 验收

- 修改前缓存命中旧数据。
- 修改接口成功后Key消失。
- 下次查询加载MySQL新数据并重建缓存。

## 11. 接口4：修改票档后删除缓存

后续增加票档修改接口时，必须删除：

```text
活动票档列表Key
具体票档库存Key
```

缓存失效属于Service编排，不属于Mapper。

## 12. 接口5：手动预热活动

```http
POST /api/admin/cache/events/{eventId}/warm-up
```

管理端接口只触发动作，具体流程在Service：

```text
1. 查询活动
2. 查询活动全部票档
3. 写入活动详情缓存
4. 写入票档列表缓存
5. 为每个票档写入库存Key
6. 返回预热结果
```

库存Key初始值来自MySQL的 `available_stock`。

### 验收

- 活动和全部票档Key都存在。
- Redis库存与MySQL一致。
- 不存在活动不能预热。

## 13. 接口6：清理活动缓存

```http
DELETE /api/admin/cache/events/{eventId}
```

删除范围：

```text
活动详情
活动票档列表
该活动下各票档库存Key
空值标记
```

不要在生产代码中使用阻塞式 `KEYS flash:ticket:*` 扫描全部Key。根据活动和票档ID精确删除。

## 14. 接口7：查询Redis展示库存

可以让现有票档查询VO增加展示库存来源，但不要把它作为最终下单依据。

查询规则：

```text
Redis存在 → 返回Redis展示值
Redis不存在 → 查询MySQL并回填
```

不要提供任意修改Redis库存的公共接口。

## 15. 缓存一致性边界

本阶段接受短时间缓存不一致，但需要保证：

```text
数据库更新成功后主动删除缓存
缓存丢失可以从MySQL重建
下单最终由MySQL条件更新兜底
```

不要在本阶段尝试解决所有分布式一致性问题。

## 16. 测试顺序

```text
1. Redis连接测试
2. 活动详情首次加载
3. 活动详情二次命中
4. 不存在活动空值缓存
5. 票档列表缓存
6. 修改活动后缓存失效
7. 预热全部Key
8. 清理全部Key
9. Redis不可用时的错误表现
```

Redis不可用时，初学版可以明确返回系统繁忙；后续再设计降级到MySQL，避免在没有限流时把全部流量瞬间压回数据库。

## 17. 阶段验收清单

- [ ] Redis连接和JSON序列化正常。
- [ ] Key命名统一。
- [ ] 活动和票档查询使用Cache-Aside。
- [ ] 不存在数据有短期空值保护。
- [ ] 更新数据库后删除相关缓存。
- [ ] 预热数据来自MySQL。
- [ ] 下单仍有MySQL最终保护。
- [ ] 没有使用生产危险的全量KEYS扫描。

## 18. 下一阶段

下一阶段使用Lua脚本把“检查重复、检查库存、扣减库存、记录资格”合并为一个Redis原子操作，并增加失败补偿。

## 19. 官方参考

- Spring Data Redis：`https://docs.spring.io/spring-data/redis/reference/`
- Spring Boot Redis支持：`https://docs.spring.io/spring-boot/3.5/reference/data/nosql.html`
- Redis Key过期：`https://redis.io/docs/latest/commands/expire/`

## 20. 完整代码答案

沿用第5阶段代码。先增加Redis依赖和配置，再实现只读缓存服务，最后让Controller切换到查询服务。MySQL仍是事实来源；缓存读取失败时回源MySQL。

### 20.1 `pom.xml` 中新增的完整依赖项

把下面依赖放入现有 `<dependencies>`，其余依赖保持不变。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### 20.2 完整 `src/main/resources/application.yml`

```yaml
spring:
  application:
    name: flash-ticket
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/flash_ticket?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:root}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2s
server:
  port: ${SERVER_PORT:8080}
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
```

### 20.3 配置层：Key和JSON缓存工具

`src/main/java/com/example/flashticket/config/CacheKeys.java`

```java
package com.example.flashticket.config;
public final class CacheKeys {
    private CacheKeys() {}
    public static String event(Long id) { return "ft:event:" + id; }
    public static String stocks(Long eventId) { return "ft:event:" + eventId + ":stocks"; }
    public static String displayStock(Long stockId) { return "ft:stock:" + stockId + ":available"; }
}
```

`src/main/java/com/example/flashticket/service/RedisJsonCache.java`

```java
package com.example.flashticket.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisJsonCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    public RedisJsonCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis=redis; this.objectMapper=objectMapper;
    }
    public <T> Optional<T> get(String key, Class<T> type) {
        String json=redis.opsForValue().get(key);
        if (json==null) return Optional.empty();
        try { return Optional.of(objectMapper.readValue(json,type)); }
        catch (Exception e) { redis.delete(key); return Optional.empty(); }
    }
    public <T> Optional<T> get(String key, TypeReference<T> type) {
        String json=redis.opsForValue().get(key);
        if (json==null) return Optional.empty();
        try { return Optional.of(objectMapper.readValue(json,type)); }
        catch (Exception e) { redis.delete(key); return Optional.empty(); }
    }
    public void put(String key,Object value,Duration ttl) {
        try { redis.opsForValue().set(key,objectMapper.writeValueAsString(value),ttl); }
        catch (Exception e) { throw new IllegalStateException("缓存序列化失败",e); }
    }
    public void delete(String... keys) { redis.delete(java.util.List.of(keys)); }
}
```

### 20.4 业务层：查询缓存与写后失效

`src/main/java/com/example/flashticket/service/TicketQueryService.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.config.CacheKeys;
import com.example.flashticket.vo.TicketVos.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TicketQueryService {
    private static final Duration TTL=Duration.ofMinutes(10);
    private final RedisJsonCache cache;
    private final TicketEventService eventService;
    private final TicketStockService stockService;
    public TicketQueryService(RedisJsonCache cache,TicketEventService eventService,TicketStockService stockService) {
        this.cache=cache; this.eventService=eventService; this.stockService=stockService;
    }
    public EventVo getEvent(Long id) {
        return cache.get(CacheKeys.event(id),EventVo.class).orElseGet(() -> {
            EventVo value=eventService.get(id); cache.put(CacheKeys.event(id),value,TTL); return value;
        });
    }
    public List<StockVo> getStocks(Long eventId) {
        return cache.get(CacheKeys.stocks(eventId),new TypeReference<List<StockVo>>() {}).orElseGet(() -> {
            List<StockVo> value=stockService.listByEvent(eventId);
            cache.put(CacheKeys.stocks(eventId),value,TTL);
            value.forEach(s -> cache.put(CacheKeys.displayStock(s.id()),s.availableStock(),TTL));
            return value;
        });
    }
    public void evictEvent(Long eventId) { cache.delete(CacheKeys.event(eventId),CacheKeys.stocks(eventId)); }
}
```

`src/main/java/com/example/flashticket/service/TicketCommandFacade.java`

```java
package com.example.flashticket.service;
import com.example.flashticket.dto.TicketDtos.*;
import com.example.flashticket.vo.TicketVos.*;
import org.springframework.stereotype.Service;
@Service
public class TicketCommandFacade {
    private final TicketEventService events; private final TicketStockService stocks; private final TicketQueryService query;
    public TicketCommandFacade(TicketEventService events,TicketStockService stocks,TicketQueryService query) {
        this.events=events; this.stocks=stocks; this.query=query;
    }
    public EventVo createEvent(CreateEventRequest r) { return events.create(r); }
    public EventVo updateEvent(Long id,UpdateEventRequest r) { EventVo v=events.update(id,r); query.evictEvent(id); return v; }
    public EventVo changeStatus(Long id,ChangeEventStatusRequest r) { EventVo v=events.changeStatus(id,r); query.evictEvent(id); return v; }
    public StockVo createStock(Long eventId,CreateStockRequest r) { StockVo v=stocks.create(eventId,r); query.evictEvent(eventId); return v; }
}
```

### 20.5 接口层完整替换

`src/main/java/com/example/flashticket/controller/TicketEventController.java`

```java
package com.example.flashticket.controller;
import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.dto.TicketDtos.*;
import com.example.flashticket.service.*;
import com.example.flashticket.vo.TicketVos.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/events")
public class TicketEventController {
    private final TicketEventService events; private final TicketQueryService query; private final TicketCommandFacade commands;
    public TicketEventController(TicketEventService events,TicketQueryService query,TicketCommandFacade commands) {
        this.events=events; this.query=query; this.commands=commands;
    }
    @PostMapping public ApiResponse<EventVo> create(@Valid @RequestBody CreateEventRequest r){return ApiResponse.success(commands.createEvent(r));}
    @GetMapping("/{id}") public ApiResponse<EventVo> get(@PathVariable Long id){return ApiResponse.success(query.getEvent(id));}
    @GetMapping public ApiResponse<PageResult<EventVo>> page(@RequestParam(defaultValue="1")long page,@RequestParam(defaultValue="10")long size){return ApiResponse.success(events.page(page,size));}
    @PutMapping("/{id}") public ApiResponse<EventVo> update(@PathVariable Long id,@Valid @RequestBody UpdateEventRequest r){return ApiResponse.success(commands.updateEvent(id,r));}
    @PatchMapping("/{id}/status") public ApiResponse<EventVo> status(@PathVariable Long id,@Valid @RequestBody ChangeEventStatusRequest r){return ApiResponse.success(commands.changeStatus(id,r));}
}
```

`src/main/java/com/example/flashticket/controller/TicketStockController.java`

```java
package com.example.flashticket.controller;
import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.dto.TicketDtos.CreateStockRequest;
import com.example.flashticket.service.*;
import com.example.flashticket.vo.TicketVos.StockVo;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/events/{eventId}/stocks")
public class TicketStockController {
    private final TicketQueryService query; private final TicketCommandFacade commands;
    public TicketStockController(TicketQueryService query,TicketCommandFacade commands){this.query=query;this.commands=commands;}
    @PostMapping public ApiResponse<StockVo> create(@PathVariable Long eventId,@Valid @RequestBody CreateStockRequest r){return ApiResponse.success(commands.createStock(eventId,r));}
    @GetMapping public ApiResponse<List<StockVo>> list(@PathVariable Long eventId){return ApiResponse.success(query.getStocks(eventId));}
}
```

`src/main/java/com/example/flashticket/controller/CacheAdminController.java`

```java
package com.example.flashticket.controller;
import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.config.CacheKeys;
import com.example.flashticket.service.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/cache")
public class CacheAdminController {
    private final TicketQueryService query; private final RedisJsonCache cache; private final StringRedisTemplate redis;
    public CacheAdminController(TicketQueryService query,RedisJsonCache cache,StringRedisTemplate redis){this.query=query;this.cache=cache;this.redis=redis;}
    @PostMapping("/events/{id}/warm") public ApiResponse<Void> warm(@PathVariable Long id){query.getEvent(id);query.getStocks(id);return ApiResponse.success();}
    @DeleteMapping("/events/{id}") public ApiResponse<Void> evict(@PathVariable Long id){query.evictEvent(id);return ApiResponse.success();}
    @GetMapping("/stocks/{id}") public ApiResponse<String> displayStock(@PathVariable Long id){return ApiResponse.success(redis.opsForValue().get(CacheKeys.displayStock(id)));}
}
```

### 20.6 验证顺序

第一次查询后观察MySQL SQL，第二次相同查询不应再出现SQL。修改活动后再次查询必须出现一次SQL并返回新值。关闭Redis时本阶段写操作可能失败，因此学习环境先保证Redis可用；生产环境还需要缓存异常降级策略。

