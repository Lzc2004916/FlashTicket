# 第3阶段：MyBatis-Plus活动与票档CRUD

## 1. 阶段目标

本阶段完成第一个真正的业务模块：活动和票档管理。

### 接口实现顺序

严格按照以下顺序实现，每个接口完成并验收后再进入下一个：

```text
1. 创建活动
2. 查询活动详情
3. 分页查询活动
4. 修改活动
5. 修改活动状态
6. 为活动新增票档
7. 查询活动票档
```

本阶段不实现用户、订单、Redis或并发抢票。

## 2. 分层职责边界

| 层 | 类 | 职责 |
| --- | --- | --- |
| 接口层 | `TicketEventController`、`TicketStockController` | 接收DTO、触发校验、调用Service、包装结果 |
| 业务层 | `TicketEventService`、`TicketStockService` | 时间规则、状态规则、重复票档检查、Entity与VO转换 |
| 数据访问层 | `TicketEventMapper`、`TicketStockMapper` | 使用MyBatis-Plus执行单表CRUD和条件查询 |
| 配置层 | `MybatisPlusConfig` | 注册分页插件等MyBatis-Plus基础Bean |
| 异常层 | `BusinessException`、`GlobalExceptionHandler` | 将业务错误统一转换为HTTP响应 |

调用关系：

```text
JSON请求
  ↓
Controller：参数格式是否正确
  ↓
Service：业务规则是否允许
  ↓
Mapper：执行SQL
  ↓
MySQL
  ↓
Service：转换VO
  ↓
Controller：返回ApiResponse
```

Controller禁止直接注入Mapper。

## 3. 需要增加的依赖

参数校验：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

MyBatis-Plus 3.5.9以后分页插件依赖拆分，使用分页时加入：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
    <version>3.5.18</version>
</dependency>
```

## 4. 公共返回与异常

先完成公共基础，否则每个Controller会重复处理错误。

### `ApiResponse<T>`

建议字段：

```java
public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> failure(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

### `BusinessException`

保存业务错误码和可展示消息，不要在Service中返回 `null` 表示失败。

### `GlobalExceptionHandler`

至少处理：

```text
BusinessException
MethodArgumentNotValidException
HttpMessageNotReadableException
Exception
```

未知异常只向客户端返回通用提示，详细堆栈写入日志。

## 5. Entity设计

### `TicketEvent`

字段与 `ticket_event` 对应：

```text
id
name
description
venue
eventTime
saleStartTime
saleEndTime
status
createTime
updateTime
```

### `TicketStock`

```text
id
eventId
ticketType
price
totalStock
availableStock
version
createTime
updateTime
```

Entity只负责数据库映射，不添加 `@NotBlank` 等接口校验注解。

## 6. Mapper设计

```java
@Mapper
public interface TicketEventMapper extends BaseMapper<TicketEvent> {
}

@Mapper
public interface TicketStockMapper extends BaseMapper<TicketStock> {
}
```

也可以在启动类配置：

```java
@MapperScan("com.example.flashticket.mapper")
```

二选一即可，不要重复添加无意义配置。

## 7. 配置分页插件

```java
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

配置层只创建插件Bean，不写任何活动查询逻辑。

## 8. 接口1：创建活动

### 请求

```http
POST /api/events
Content-Type: application/json
```

```json
{
  "name": "Java开发者大会",
  "description": "Spring与高并发专题",
  "venue": "上海国际会议中心",
  "eventTime": "2027-01-20T19:30:00",
  "saleStartTime": "2026-12-01T10:00:00",
  "saleEndTime": "2027-01-19T23:59:59"
}
```

### DTO

`CreateTicketEventRequest`负责格式校验：

```text
name：@NotBlank，最大100字符
description：最大500字符
venue：@NotBlank，最大150字符
eventTime：@NotNull
saleStartTime：@NotNull
saleEndTime：@NotNull
```

### Controller职责

```java
@PostMapping
public ApiResponse<TicketEventVO> create(
        @Valid @RequestBody CreateTicketEventRequest request) {
    return ApiResponse.success(ticketEventService.create(request));
}
```

Controller不判断时间先后。

### Service职责

按顺序执行：

```text
1. 校验 saleStartTime < saleEndTime
2. 校验 saleEndTime <= eventTime
3. 构造TicketEvent
4. 初始状态固定为DRAFT
5. 调用Mapper.insert
6. 确认影响行数为1
7. 转换为TicketEventVO
```

### Mapper职责

只执行：

```java
ticketEventMapper.insert(entity);
```

### 接口验收

- HTTP返回成功。
- 返回值包含数据库生成的ID。
- 数据库新增一行。
- 状态是 `DRAFT`，不能由客户端指定。
- 错误时间顺序返回业务错误。

全部通过后再实现接口2。

## 9. 接口2：查询活动详情

### 请求

```http
GET /api/events/{id}
```

### Service流程

```text
1. 使用selectById查询
2. 不存在则抛出“活动不存在”
3. 转换为TicketEventVO
4. 返回给Controller
```

不要让Controller判断：

```java
if (event == null) { ... }
```

这是业务层责任。

### 接口验收

- 存在的ID返回完整活动信息。
- 不存在的ID返回统一业务错误结构。
- 响应中不暴露数据库内部无用字段。

## 10. 接口3：分页查询活动

### 请求

```http
GET /api/events?page=1&size=10&status=DRAFT&keyword=Java
```

### Controller职责

- 接收页码、页大小、状态和关键词。
- 页大小限制在1至100。
- 调用Service。

### Service职责

构造 `LambdaQueryWrapper<TicketEvent>`：

```text
status不为空：eq(status)
keyword不为空：like(name, keyword)
按create_time倒序
```

调用：

```java
ticketEventMapper.selectPage(page, wrapper);
```

返回分页VO：

```text
records
page
size
total
pages
```

### 接口验收

- 第一页和第二页数据不重复。
- 状态筛选正确。
- 关键词为空时不添加like条件。
- `size=1000` 被校验拒绝或限制。

## 11. 接口4：修改活动

### 请求

```http
PUT /api/events/{id}
```

使用 `UpdateTicketEventRequest`，不要复用创建DTO，因为：

- 创建接口不允许传ID和状态。
- 修改接口的字段规则可能不同。
- DTO按用例设计，而不是按表设计。

### Service流程

```text
1. 查询活动，不存在则失败
2. 只允许DRAFT状态修改基础信息
3. 校验新的时间关系
4. 修改允许修改的字段
5. updateById
6. 查询并返回最新VO
```

### 接口验收

- 草稿活动可以修改。
- 售票中活动不能随意修改开售规则。
- 请求不能修改库存和订单。
- 不存在ID返回业务异常。

## 12. 接口5：修改活动状态

### 请求

```http
PATCH /api/events/{id}/status
```

```json
{
  "status": "ON_SALE"
}
```

### 状态规则

第一版只允许：

```text
DRAFT → ON_SALE
ON_SALE → CLOSED
DRAFT → CLOSED
```

禁止客户端任意覆盖状态。

### 上架前校验

进入 `ON_SALE` 前必须确认：

- 活动存在。
- 至少有一个票档。
- 所有票档价格大于0。
- 所有票档库存大于0。
- 售票时间关系正确。

这个校验属于Service，不属于Mapper。

### 接口验收

- 没有票档时不能上架。
- 非法状态流转被拒绝。
- 状态修改后详情接口立即返回新状态。

## 13. 接口6：新增票档

### 请求

```http
POST /api/events/{eventId}/stocks
```

```json
{
  "ticketType": "VIP票",
  "price": 399.00,
  "totalStock": 100
}
```

### DTO规则

```text
ticketType：非空，最大50字符
price：大于0，最多2位小数
totalStock：大于0
```

### Service流程

```text
1. 查询活动
2. 只允许DRAFT活动新增票档
3. 检查同活动下ticketType是否重复
4. availableStock初始化为totalStock
5. version初始化为0
6. 插入数据库
7. 返回TicketStockVO
```

数据库唯一索引仍然保留，Service预检查只是为了返回更友好的错误。

### 接口验收

- 可创建普通票和VIP票。
- 同一活动不能创建同名票档。
- 两个不同活动可以拥有同名票档。
- `available_stock` 等于 `total_stock`。

## 14. 接口7：查询活动票档

### 请求

```http
GET /api/events/{eventId}/stocks
```

### Service流程

```text
1. 确认活动存在
2. 按event_id查询票档
3. 按price升序或创建时间升序
4. 转换为TicketStockVO列表
```

### 接口验收

- 返回当前活动的全部票档。
- 不混入其他活动的票档。
- 活动不存在和“活动存在但暂无票档”要区分。

## 15. 测试策略

### Service单元测试

使用Mock Mapper验证：

- 时间规则。
- 状态流转。
- 上架前票档检查。
- 重复票档判断。

### Mapper集成测试

验证：

- Entity字段映射。
- 分页插件。
- 唯一索引。

### Controller测试

使用MockMvc验证：

- JSON格式。
- `@Valid`错误响应。
- HTTP路径和方法。

## 16. 阶段验收清单

- [ ] 七个接口严格按顺序完成。
- [ ] Controller没有注入Mapper。
- [ ] DTO与Entity分离。
- [ ] 创建活动默认DRAFT。
- [ ] 活动上架前必须存在票档。
- [ ] 票档库存初始化正确。
- [ ] 分页和筛选正确。
- [ ] 全局异常返回结构统一。
- [ ] 全部测试通过。

## 17. 下一阶段

下一阶段创建用户和普通订单，用MySQL事务完成“扣库存、创建订单、取消订单恢复库存”的完整生命周期。

## 18. 官方参考

- MyBatis-Plus快速开始：`https://baomidou.com/en/getting-started/`
- MyBatis-Plus配置：`https://baomidou.com/en/getting-started/config/`
- Spring Validation：`https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html`

## 19. 完整代码答案

下面是本阶段新增或替换文件的完整内容。未列出的启动类、数据库脚本和数据库配置沿用第2阶段。实现时仍按“创建活动→查询详情→分页→修改→状态→新增票档→查询票档”的顺序逐个验证。

### 19.1 完整 `pom.xml`

Lombok只负责生成Entity的getter/setter；DTO和VO使用Java 17的 `record`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.5.16</version><relativePath/></parent>
    <groupId>com.example</groupId><artifactId>flash-ticket</artifactId><version>0.0.1-SNAPSHOT</version>
    <properties><java.version>17</java.version><mybatis-plus.version>3.5.18</mybatis-plus.version></properties>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId><version>${mybatis-plus.version}</version></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-jsqlparser</artifactId><version>${mybatis-plus.version}</version></dependency>
        <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### 19.2 公共返回与异常层

`src/main/java/com/example/flashticket/common/ApiResponse.java`

```java
package com.example.flashticket.common;

public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> success(T data) { return new ApiResponse<>(0, "success", data); }
    public static ApiResponse<Void> success() { return new ApiResponse<>(0, "success", null); }
    public static ApiResponse<Void> failure(int code, String message) { return new ApiResponse<>(code, message, null); }
}
```

`src/main/java/com/example/flashticket/exception/BusinessException.java`

```java
package com.example.flashticket.exception;

public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) { super(message); this.code = code; }
    public int getCode() { return code; }
}
```

`src/main/java/com/example/flashticket/exception/GlobalExceptionHandler.java`

```java
package com.example.flashticket.exception;

import com.example.flashticket.common.ApiResponse;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> business(BusinessException e) { return ApiResponse.failure(e.getCode(), e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(x -> x.getField() + ": " + x.getDefaultMessage()).collect(Collectors.joining("; "));
        return ApiResponse.failure(400, message);
    }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> duplicate(DuplicateKeyException e) { return ApiResponse.failure(409, "数据已存在，请勿重复提交"); }
}
```

### 19.3 数据访问层：Entity与Mapper

`src/main/java/com/example/flashticket/entity/TicketEvent.java`

```java
package com.example.flashticket.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;
@Data @TableName("ticket_event")
public class TicketEvent {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    private String venue;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`src/main/java/com/example/flashticket/entity/TicketStock.java`

```java
package com.example.flashticket.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
@Data @TableName("ticket_stock")
public class TicketStock {
    @TableId(type = IdType.AUTO) private Long id;
    private Long eventId;
    private String tierName;
    private BigDecimal price;
    private Integer totalStock;
    private Integer availableStock;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`src/main/java/com/example/flashticket/mapper/TicketEventMapper.java`

```java
package com.example.flashticket.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashticket.entity.TicketEvent;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface TicketEventMapper extends BaseMapper<TicketEvent> {}
```

`src/main/java/com/example/flashticket/mapper/TicketStockMapper.java`

```java
package com.example.flashticket.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashticket.entity.TicketStock;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface TicketStockMapper extends BaseMapper<TicketStock> {}
```

### 19.4 DTO与VO

`src/main/java/com/example/flashticket/dto/TicketDtos.java`

```java
package com.example.flashticket.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class TicketDtos {
    private TicketDtos() {}
    public record CreateEventRequest(@NotBlank String name, @NotBlank String venue,
            @NotNull @Future LocalDateTime saleStartTime, @NotNull @Future LocalDateTime saleEndTime) {}
    public record UpdateEventRequest(@NotBlank String name, @NotBlank String venue,
            @NotNull LocalDateTime saleStartTime, @NotNull LocalDateTime saleEndTime) {}
    public record ChangeEventStatusRequest(@NotNull @Min(0) @Max(2) Integer status) {}
    public record CreateStockRequest(@NotBlank String tierName,
            @NotNull @DecimalMin("0.01") BigDecimal price, @NotNull @Min(1) Integer totalStock) {}
}
```

`src/main/java/com/example/flashticket/vo/TicketVos.java`

```java
package com.example.flashticket.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class TicketVos {
    private TicketVos() {}
    public record EventVo(Long id, String name, String venue, LocalDateTime saleStartTime,
                          LocalDateTime saleEndTime, Integer status) {}
    public record StockVo(Long id, Long eventId, String tierName, BigDecimal price,
                          Integer totalStock, Integer availableStock) {}
    public record PageResult<T>(long total, long page, long size, List<T> records) {}
}
```

### 19.5 配置层

`src/main/java/com/example/flashticket/config/MybatisPlusConfig.java`

```java
package com.example.flashticket.config;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.*;
@Configuration
public class MybatisPlusConfig {
    @Bean public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor value = new MybatisPlusInterceptor();
        value.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return value;
    }
}
```

### 19.6 业务层

`src/main/java/com/example/flashticket/service/TicketEventService.java`

```java
package com.example.flashticket.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flashticket.dto.TicketDtos.*;
import com.example.flashticket.entity.*;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.mapper.*;
import com.example.flashticket.vo.TicketVos.*;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TicketEventService {
    private final TicketEventMapper eventMapper;
    private final TicketStockMapper stockMapper;
    public TicketEventService(TicketEventMapper eventMapper, TicketStockMapper stockMapper) {
        this.eventMapper = eventMapper; this.stockMapper = stockMapper;
    }
    public EventVo create(CreateEventRequest r) {
        checkTime(r.saleStartTime(), r.saleEndTime());
        TicketEvent e = new TicketEvent();
        e.setName(r.name()); e.setVenue(r.venue()); e.setSaleStartTime(r.saleStartTime());
        e.setSaleEndTime(r.saleEndTime()); e.setStatus(0); eventMapper.insert(e); return toVo(e);
    }
    public EventVo get(Long id) { return toVo(requireEvent(id)); }
    public PageResult<EventVo> page(long page, long size) {
        Page<TicketEvent> result = eventMapper.selectPage(Page.of(page, Math.min(size, 100)), null);
        List<EventVo> records = result.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }
    public EventVo update(Long id, UpdateEventRequest r) {
        checkTime(r.saleStartTime(), r.saleEndTime());
        TicketEvent e = requireEvent(id); e.setName(r.name()); e.setVenue(r.venue());
        e.setSaleStartTime(r.saleStartTime()); e.setSaleEndTime(r.saleEndTime());
        eventMapper.updateById(e); return toVo(e);
    }
    public EventVo changeStatus(Long id, ChangeEventStatusRequest r) {
        TicketEvent e = requireEvent(id);
        long count = stockMapper.selectCount(new LambdaQueryWrapper<TicketStock>().eq(TicketStock::getEventId, id));
        if (r.status() == 1 && count == 0) throw new BusinessException(400, "至少创建一个票档后才能上架");
        e.setStatus(r.status()); eventMapper.updateById(e); return toVo(e);
    }
    public TicketEvent requireEvent(Long id) {
        TicketEvent e = eventMapper.selectById(id);
        if (e == null) throw new BusinessException(404, "活动不存在");
        return e;
    }
    private void checkTime(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) throw new BusinessException(400, "结束时间必须晚于开始时间");
    }
    private EventVo toVo(TicketEvent e) {
        return new EventVo(e.getId(), e.getName(), e.getVenue(), e.getSaleStartTime(), e.getSaleEndTime(), e.getStatus());
    }
}
```

`src/main/java/com/example/flashticket/service/TicketStockService.java`

```java
package com.example.flashticket.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashticket.dto.TicketDtos.CreateStockRequest;
import com.example.flashticket.entity.TicketStock;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.mapper.TicketStockMapper;
import com.example.flashticket.vo.TicketVos.StockVo;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TicketStockService {
    private final TicketStockMapper stockMapper;
    private final TicketEventService eventService;
    public TicketStockService(TicketStockMapper stockMapper, TicketEventService eventService) {
        this.stockMapper = stockMapper; this.eventService = eventService;
    }
    public StockVo create(Long eventId, CreateStockRequest r) {
        eventService.requireEvent(eventId);
        long count = stockMapper.selectCount(new LambdaQueryWrapper<TicketStock>()
                .eq(TicketStock::getEventId, eventId).eq(TicketStock::getTierName, r.tierName()));
        if (count > 0) throw new BusinessException(409, "同一活动不能有重名票档");
        TicketStock s = new TicketStock(); s.setEventId(eventId); s.setTierName(r.tierName());
        s.setPrice(r.price()); s.setTotalStock(r.totalStock()); s.setAvailableStock(r.totalStock()); s.setVersion(0);
        stockMapper.insert(s); return toVo(s);
    }
    public List<StockVo> listByEvent(Long eventId) {
        eventService.requireEvent(eventId);
        return stockMapper.selectList(new LambdaQueryWrapper<TicketStock>()
                .eq(TicketStock::getEventId, eventId).orderByAsc(TicketStock::getId)).stream().map(this::toVo).toList();
    }
    public TicketStock requireStock(Long id) {
        TicketStock s = stockMapper.selectById(id);
        if (s == null) throw new BusinessException(404, "票档不存在");
        return s;
    }
    private StockVo toVo(TicketStock s) {
        return new StockVo(s.getId(), s.getEventId(), s.getTierName(), s.getPrice(), s.getTotalStock(), s.getAvailableStock());
    }
}
```

### 19.7 接口层

`src/main/java/com/example/flashticket/controller/TicketEventController.java`

```java
package com.example.flashticket.controller;
import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.dto.TicketDtos.*;
import com.example.flashticket.service.TicketEventService;
import com.example.flashticket.vo.TicketVos.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/events")
public class TicketEventController {
    private final TicketEventService service;
    public TicketEventController(TicketEventService service) { this.service = service; }
    @PostMapping public ApiResponse<EventVo> create(@Valid @RequestBody CreateEventRequest r) { return ApiResponse.success(service.create(r)); }
    @GetMapping("/{id}") public ApiResponse<EventVo> get(@PathVariable Long id) { return ApiResponse.success(service.get(id)); }
    @GetMapping public ApiResponse<PageResult<EventVo>> page(@RequestParam(defaultValue="1") long page, @RequestParam(defaultValue="10") long size) { return ApiResponse.success(service.page(page, size)); }
    @PutMapping("/{id}") public ApiResponse<EventVo> update(@PathVariable Long id, @Valid @RequestBody UpdateEventRequest r) { return ApiResponse.success(service.update(id, r)); }
    @PatchMapping("/{id}/status") public ApiResponse<EventVo> status(@PathVariable Long id, @Valid @RequestBody ChangeEventStatusRequest r) { return ApiResponse.success(service.changeStatus(id, r)); }
}
```

`src/main/java/com/example/flashticket/controller/TicketStockController.java`

```java
package com.example.flashticket.controller;
import com.example.flashticket.common.ApiResponse;
import com.example.flashticket.dto.TicketDtos.CreateStockRequest;
import com.example.flashticket.service.TicketStockService;
import com.example.flashticket.vo.TicketVos.StockVo;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/events/{eventId}/stocks")
public class TicketStockController {
    private final TicketStockService service;
    public TicketStockController(TicketStockService service) { this.service = service; }
    @PostMapping public ApiResponse<StockVo> create(@PathVariable Long eventId, @Valid @RequestBody CreateStockRequest r) { return ApiResponse.success(service.create(eventId, r)); }
    @GetMapping public ApiResponse<List<StockVo>> list(@PathVariable Long eventId) { return ApiResponse.success(service.listByEvent(eventId)); }
}
```

### 19.8 验证方法

先执行 `.\mvnw.cmd clean test`，再启动应用。严格使用正文第8至14节的请求逐个验证；一个接口的成功场景、参数错误和数据不存在场景都通过后，再实现下一个接口。

