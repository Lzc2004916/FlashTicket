# 第5阶段：MySQL并发防超卖与幂等

## 1. 阶段目标

本阶段不增加新的主要业务接口，而是增强现有下单和取消接口，使其在单个Spring Boot实例的并发请求下仍然正确。

核心保证：

```text
库存永远不小于0
成功订单数量不超过库存
同一用户不能重复购买同一票档
扣库存和创建订单同时成功或同时回滚
取消订单最多恢复一次库存
```

### 接口实现顺序

本阶段是在第4阶段已有接口上逐个增加并发保护，不要同时改完后再统一测试。严格按照以下顺序进行，每完成一项并通过对应的并发测试后，再进入下一项：

```text
1. 改造创建订单：使用条件更新原子扣减库存
2. 改造创建订单：通过唯一索引防止重复下单
3. 改造支付订单：通过条件状态更新保证幂等
4. 改造取消订单：仅首次取消时恢复库存
```

接口路径和请求参数保持不变，变化集中在Service、Mapper和数据库约束中。

## 2. 为什么第4阶段写法会超卖

危险流程：

```text
线程A查询库存=1
线程B查询库存=1
线程A把库存更新为0
线程B也把库存更新为0
最终生成两个订单
```

问题不在 `if` 判断，而在“查询”和“更新”是两条独立SQL，中间可以被其他事务插入。

## 3. 分层职责边界

| 层 | 本阶段职责 |
| --- | --- |
| 接口层 | 接口路径和DTO保持不变，不感知锁和SQL细节 |
| 业务层 | 组织条件扣库存、插入订单、异常回滚和取消恢复 |
| 数据访问层 | 提供原子库存更新SQL和条件状态更新SQL |
| 配置层 | 事务管理使用Spring Boot默认配置，不在配置类中写并发规则 |
| 数据库 | 条件更新、唯一索引和事务是最终一致性保护 |

调用关系：

```text
Controller
  ↓
TicketOrderService @Transactional
  ├─ TicketStockMapper.decreaseStock()
  └─ TicketOrderMapper.insert()
       ↓
MySQL原子更新 + 唯一索引
```

## 4. 改造接口1：创建订单

接口仍然是：

```http
POST /api/orders
```

### Mapper新增原子扣库存

```java
@Update("""
    UPDATE ticket_stock
    SET available_stock = available_stock - 1,
        version = version + 1
    WHERE id = #{stockId}
      AND available_stock > 0
    """)
int decreaseStock(@Param("stockId") Long stockId);
```

这一条SQL同时完成：

```text
检查库存 > 0
扣减库存
```

MySQL把单条UPDATE作为原子操作执行。

### Service新流程

```text
1. 查询并校验用户、活动、票档基础信息
2. 可做一次重复订单友好检查
3. 调用decreaseStock(stockId)
4. 影响行数为0：抛出“库存不足”
5. 插入订单
6. 唯一索引冲突：转换为“不能重复购买”
7. 任意异常触发事务回滚
```

注意：Service的“是否已购买”查询不能代替唯一索引，因为两个请求可能同时查询到不存在。

### 为什么先扣库存再插订单

先扣库存可以快速拒绝售罄请求。若插入订单因为唯一索引失败，事务回滚会恢复库存。

### 验收

使用库存10，创建100个不同用户并发下单：

```text
成功订单=10
available_stock=0
没有负库存
```

## 5. 改造接口2：防止同一用户重复下单

接口仍然是：

```http
POST /api/orders
```

最终保护：

```sql
UNIQUE KEY uk_ticket_order_user_stock (user_id, stock_id)
```

并发场景：

```text
同一用户同时发送20次请求
→ 最多1条订单插入成功
→ 其余请求命中唯一索引
→ 对应事务回滚库存
```

Service捕获 `DuplicateKeyException` 时必须区分可能的唯一索引：

- 订单号重复可以重新生成后有限重试。
- 用户票档重复直接返回“不能重复购买”。

初学阶段可以使用不同异常消息或数据库约束名辅助判断，但不要无限重试。

## 6. 改造接口3：并发支付

```http
POST /api/orders/{orderNo}/pay
```

不要采用“先查询状态，再updateById”。使用条件更新：

```sql
UPDATE ticket_order
SET status = 'PAID',
    pay_time = NOW()
WHERE order_no = ?
  AND status = 'PENDING';
```

影响行数：

```text
1：本次支付状态变更成功
0：订单不存在或状态已经变化
```

随后查询订单，区分不存在、已支付和已取消。

## 7. 改造接口4：并发取消订单

```http
POST /api/orders/{orderNo}/cancel
```

关键问题：两个线程不能都把库存加1。

事务流程：

```text
1. 条件更新订单 PENDING → CANCELLED
2. 影响行数为0则停止，不恢复库存
3. 影响行数为1才执行库存+1
4. 两条更新在同一事务中
```

订单条件更新：

```sql
UPDATE ticket_order
SET status = 'CANCELLED',
    cancel_time = NOW()
WHERE order_no = ?
  AND status = 'PENDING';
```

库存恢复：

```sql
UPDATE ticket_stock
SET available_stock = available_stock + 1,
    version = version + 1
WHERE id = ?
  AND available_stock < total_stock;
```

只有成功抢到“取消资格”的线程才能恢复库存一次。

## 8. 幂等的含义

幂等不是“接口永远返回同样的HTTP状态”，而是：

> 同一个业务请求执行一次或多次，最终业务结果不会被重复修改。

本阶段的幂等保护：

| 操作 | 保护方式 |
| --- | --- |
| 重复下单 | 用户+票档唯一索引 |
| 重复支付 | `WHERE status='PENDING'` 条件更新 |
| 重复取消 | 订单状态条件更新，只有首次恢复库存 |
| 库存扣减 | `available_stock > 0` 条件更新 |

## 9. 事务边界

下单事务：

```text
条件扣库存
插入订单
```

取消事务：

```text
订单条件改为CANCELLED
恢复库存
```

不要把整个Controller标记为事务，因为HTTP序列化和其他无关操作不应占用数据库事务。

## 10. 并发测试

### 测试1：多人抢少量库存

```text
库存：10
用户：100
线程数：20
```

使用 `ExecutorService` 和 `CountDownLatch` 同时发起Service调用。

最终断言：

```java
assertEquals(10, successCount.get());
assertEquals(0, stock.getAvailableStock());
assertEquals(10, orderCount);
```

### 测试2：同一用户重复抢票

```text
库存：100
同一用户并发请求：20
```

断言：

```text
订单数量=1
库存只减少1
```

### 测试3：重复取消

对一个PENDING订单并发取消20次：

```text
订单最终=CANCELLED
库存只增加1
```

## 11. 接口验证顺序

严格按顺序执行：

```text
1. 单线程创建订单仍然正常
2. 多用户并发创建订单不超卖
3. 同用户并发创建订单不重复
4. 单线程支付正常
5. 并发支付只有一次状态变化
6. 单线程取消正常
7. 并发取消只恢复一次库存
```

每一步失败时不要继续增加Redis。

## 12. 数据库隔离级别

初学阶段保持MySQL InnoDB默认隔离级别即可。当前正确性主要来自：

```text
单条条件UPDATE
唯一索引
事务回滚
```

不要一开始通过提高隔离级别或给所有查询加锁解决问题，这会增加锁竞争。

## 13. 阶段验收清单

- [ ] 下单接口没有超卖。
- [ ] 同一用户没有重复订单。
- [ ] 订单插入失败时库存回滚。
- [ ] 支付状态条件更新。
- [ ] 取消只恢复一次库存。
- [ ] Controller接口契约没有因为并发实现而改变。
- [ ] 并发测试可以稳定重复运行。

## 14. 下一阶段

下一阶段引入Redis，但MySQL的条件更新、唯一索引和事务仍然保留。Redis用于减少数据库压力，不替代数据库的最终保护。

## 15. 完整代码答案

本阶段不改Controller和DTO，只完整替换下面3个文件。核心原则是：Service负责事务顺序，Mapper负责一条SQL的原子条件，数据库唯一索引负责最后兜底。

### 文件1：`src/main/java/com/example/flashticket/mapper/TicketStockMapper.java`

```java
package com.example.flashticket.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashticket.entity.TicketStock;
import org.apache.ibatis.annotations.*;
@Mapper
public interface TicketStockMapper extends BaseMapper<TicketStock> {
    @Update("UPDATE ticket_stock SET available_stock=available_stock-1, version=version+1 " +
            "WHERE id=#{stockId} AND available_stock>0")
    int decreaseStock(@Param("stockId") Long stockId);

    @Update("UPDATE ticket_stock SET available_stock=available_stock+1, version=version+1 WHERE id=#{stockId}")
    int increaseStock(@Param("stockId") Long stockId);
}
```

### 文件2：`src/main/java/com/example/flashticket/mapper/TicketOrderMapper.java`

```java
package com.example.flashticket.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashticket.entity.TicketOrder;
import org.apache.ibatis.annotations.*;
@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {
    @Update("UPDATE ticket_order SET status=#{toStatus}, updated_at=NOW() " +
            "WHERE id=#{id} AND status=#{fromStatus}")
    int changeStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);
}
```

### 文件3：`src/main/java/com/example/flashticket/service/TicketOrderService.java`

```java
package com.example.flashticket.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashticket.dto.OrderDtos.CreateOrderRequest;
import com.example.flashticket.entity.*;
import com.example.flashticket.exception.BusinessException;
import com.example.flashticket.mapper.*;
import com.example.flashticket.vo.OrderVos.OrderVo;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketOrderService {
    private final TicketOrderMapper orderMapper;
    private final TicketStockMapper stockMapper;
    private final TicketEventMapper eventMapper;
    private final AppUserService userService;
    public TicketOrderService(TicketOrderMapper orderMapper, TicketStockMapper stockMapper,
            TicketEventMapper eventMapper, AppUserService userService) {
        this.orderMapper=orderMapper; this.stockMapper=stockMapper;
        this.eventMapper=eventMapper; this.userService=userService;
    }

    @Transactional
    public OrderVo create(CreateOrderRequest r) {
        userService.requireUser(r.userId());
        TicketStock stock = stockMapper.selectById(r.stockId());
        if (stock == null) throw new BusinessException(404, "票档不存在");
        TicketEvent event = eventMapper.selectById(stock.getEventId());
        if (event == null || event.getStatus() != 1) throw new BusinessException(400, "活动未上架");
        if (stockMapper.decreaseStock(stock.getId()) != 1)
            throw new BusinessException(409, "库存不足");

        TicketOrder order = new TicketOrder();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(r.userId()); order.setEventId(event.getId()); order.setStockId(stock.getId());
        order.setEventName(event.getName()); order.setTierName(stock.getTierName());
        order.setAmount(stock.getPrice()); order.setStatus(0);
        orderMapper.insert(order); // 重复用户触发唯一索引；异常使整个事务回滚，库存也会恢复
        return toVo(order);
    }

    public OrderVo get(Long id) { return toVo(requireOrder(id)); }
    public List<OrderVo> listByUser(Long userId) {
        userService.requireUser(userId);
        return orderMapper.selectList(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId,userId).orderByDesc(TicketOrder::getId))
                .stream().map(this::toVo).toList();
    }

    @Transactional
    public OrderVo pay(Long id) {
        if (orderMapper.changeStatus(id, 0, 1) == 1) return get(id);
        TicketOrder current = requireOrder(id);
        if (current.getStatus() == 1) return toVo(current); // 重复支付：结果不变
        throw new BusinessException(400, "已取消订单不能支付");
    }

    @Transactional
    public OrderVo cancel(Long id) {
        if (orderMapper.changeStatus(id, 0, 2) == 1) {
            TicketOrder changed = requireOrder(id);
            stockMapper.increaseStock(changed.getStockId());
            return toVo(changed);
        }
        TicketOrder current = requireOrder(id);
        if (current.getStatus() == 2) return toVo(current); // 重复取消：不再恢复库存
        throw new BusinessException(400, "已支付订单不能取消");
    }

    public TicketOrder requireOrder(Long id) {
        TicketOrder order=orderMapper.selectById(id);
        if (order==null) throw new BusinessException(404,"订单不存在");
        return order;
    }
    private OrderVo toVo(TicketOrder o) {
        return new OrderVo(o.getId(),o.getOrderNo(),o.getUserId(),o.getEventId(),o.getStockId(),
                o.getEventName(),o.getTierName(),o.getAmount(),o.getStatus(),o.getCreatedAt());
    }
}
```

### 数据库约束复核

第2阶段脚本中的两个唯一索引必须存在：

```sql
SHOW INDEX FROM ticket_order WHERE Key_name IN ('uk_order_no', 'uk_user_stock');
```

### 完整并发验证

先把某票档库存设为10，再用20个不同用户并发调用 `POST /api/orders`。最终必须满足：成功订单10条、`available_stock=0`、没有负库存。同一用户并发请求10次时最多成功1次。最后对同一待支付订单并发取消10次，库存只能增加1。

