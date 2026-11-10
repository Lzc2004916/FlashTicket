# 第12阶段：压测、监控与JVM分析

## 1. 阶段目标

本阶段不以“调一堆JVM参数”为目标，而是建立完整的性能分析流程：

```text
建立基线
→ 施加可重复负载
→ 收集应用、JVM、数据库和Redis指标
→ 定位瓶颈
→ 一次只修改一个因素
→ 重新压测并对比
```

实现顺序：

```text
1. 暴露健康和指标接口
2. 建立日志关联ID
3. 创建活动查询压测
4. 创建抢票压测
5. 校验业务正确性
6. 分析线程、连接池和外部依赖
7. 开启GC日志
8. 录制JFR
9. 完成一次有证据的优化
10. 输出测试报告
```

## 2. 分层职责边界

| 层 | 职责 |
| --- | --- |
| 接口层 | 暴露业务接口；Actuator独立暴露基础设施接口 |
| 业务层 | 添加关键业务计数和耗时，不读取JVM内部实现 |
| 数据访问层 | 关注SQL耗时、连接池使用和慢查询 |
| 配置层 | Actuator、Micrometer、线程池、日志、JVM启动参数 |
| 压测工具 | 产生可重复请求，不修改业务数据规则 |
| 分析工具 | JFR、GC日志、线程转储、堆转储 |

监控代码不能改变下单结果，也不能在高频路径打印大量同步日志。

## 3. 增加监控依赖

已有Actuator时继续使用。增加Prometheus Registry：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

配置：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,threaddump,loggers
  endpoint:
    health:
      show-details: when_authorized
  metrics:
    tags:
      application: flash-ticket
```

生产环境必须保护Actuator，不能把敏感端点直接暴露到公网。

## 4. 接口1：健康检查

```http
GET /actuator/health
```

检查：

```text
应用状态
MySQL连接
Redis连接
RabbitMQ连接
磁盘空间
```

健康检查不等于性能正常，只说明组件是否可以响应基本检查。

## 5. 接口2：JVM和应用指标

```http
GET /actuator/metrics
GET /actuator/metrics/jvm.memory.used
GET /actuator/metrics/jvm.gc.pause
GET /actuator/metrics/process.cpu.usage
GET /actuator/prometheus
```

重点指标：

```text
jvm.memory.used
jvm.memory.max
jvm.gc.pause
jvm.threads.live
jvm.threads.peak
process.cpu.usage
system.cpu.usage
http.server.requests
hikaricp.connections.active
hikaricp.connections.pending
rabbitmq相关指标
lettuce/redis调用指标或自定义耗时
```

## 6. 自定义业务指标

通过Micrometer记录：

```text
抢票请求总数
Lua成功数
售罄数
重复购买数
消息发布失败数
订单创建成功数
订单创建失败数
补偿执行数
死信消息数
```

标签必须保持低基数。可以使用：

```text
status=success/sold_out/duplicate/error
```

不要把以下内容作为标签：

```text
userId
orderNo
requestId
```

否则会产生海量时间序列。

## 7. 请求关联ID

为每次HTTP请求生成或接收 `X-Request-Id`：

```text
HTTP入口
→ 日志MDC
→ RabbitMQ消息
→ Consumer日志
→ WebSocket通知
```

这样可以追踪一个请求跨越多个线程和组件的完整链路。

关联ID放在Filter或Interceptor中处理，不在每个Controller重复编写。

## 8. 压测1：活动查询接口

目标接口：

```http
GET /api/events/{id}
GET /api/events/{eventId}/stocks
```

JMeter场景：

```text
并发用户：50、100、200逐级增加
预热：30秒
持续时间：3至5分钟
固定活动ID和一组随机活动ID分别测试
```

记录：

```text
吞吐量
平均响应时间
P95
P99
错误率
Redis命中率
MySQL查询量
```

先验证缓存命中场景，再验证缓存失效重建场景。

完成查询接口压测并形成基线后，再进入抢票压测。

## 9. 压测2：抢票接口

目标：

```http
POST /api/seckill/orders
```

场景示例：

```text
库存：100
用户：1000个不同userId
并发：200
持续时间：短时间突发
每个用户只提交一次
```

请求数据必须包含不同用户，不能所有线程都使用一个userId，否则测到的是重复购买保护，不是真实库存竞争。

记录：

```text
QUEUED响应时间
Lua返回分布
消息积压数量
消费者处理速度
订单最终完成时间
成功订单数量
补偿数量
死信数量
```

## 10. 业务正确性校验

性能数据有效之前，必须先通过业务校验：

```sql
SELECT available_stock FROM ticket_stock WHERE id = ?;

SELECT COUNT(*) FROM ticket_order WHERE stock_id = ?;

SELECT user_id, stock_id, COUNT(*)
FROM ticket_order
GROUP BY user_id, stock_id
HAVING COUNT(*) > 1;
```

同时检查Redis：

```text
库存是否为负
users集合大小
QUEUED超时请求数量
Redis与MySQL差值
```

如果业务数据错误，不能以高QPS作为成功结果。

## 11. 定位常见瓶颈

### CPU高

检查：

```text
JSON序列化
日志格式化
忙循环
过多线程切换
Lua或Java计算热点
```

### Hikari等待连接

观察：

```text
hikaricp.connections.active
hikaricp.connections.pending
SQL执行时间
事务持续时间
```

不要只增大连接池，先确认慢SQL和过长事务。

### RabbitMQ积压

检查：

```text
生产速度
消费者数量
单条消费耗时
数据库写入能力
重试循环
```

### Redis延迟

检查：

```text
热点Key
大Key
慢Lua脚本
连接池等待
Cluster重定向
```

## 12. 线程转储

```powershell
jcmd <PID> Thread.print > thread-dump.txt
```

重点查看：

```text
大量BLOCKED线程
等待数据库连接的线程
RabbitMQ消费者阻塞
死锁
自定义线程池队列堆积
```

线程多不一定是问题，关键是线程在做什么以及是否持续堆积。

## 13. GC日志

Java 17启动参数示例：

```powershell
java `
  -Xms512m `
  -Xmx512m `
  -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=20m `
  -jar target\flash-ticket-0.0.1-SNAPSHOT.jar
```

先固定堆大小便于对比测试，不代表512MB一定是生产最佳值。

观察：

```text
Young GC频率
单次暂停时间
Full GC
晋升失败
堆使用趋势
```

## 14. JFR录制

启动时录制：

```powershell
java `
  -XX:StartFlightRecording=filename=logs/flash-ticket.jfr,duration=5m,settings=profile `
  -jar target\flash-ticket-0.0.1-SNAPSHOT.jar
```

或运行中：

```powershell
jcmd <PID> JFR.start name=flash-ticket settings=profile duration=5m filename=logs/flash-ticket.jfr
```

使用JDK Mission Control查看：

```text
方法热点
对象分配热点
锁竞争
线程状态
Socket与文件IO
GC事件
```

## 15. 调优实验规则

一次只修改一个变量，例如：

```text
消费者并发数从4改为8
或Hikari最大连接从10改为20
或减少同步日志
或优化一条慢SQL索引
```

每次记录：

```text
修改前配置
修改后配置
相同压测脚本
吞吐变化
P95/P99变化
错误率变化
资源使用变化
业务正确性结果
```

不要同时修改十个参数，否则无法知道哪个变化有效。

## 16. 第一次推荐优化实验

建议从可解释的问题开始：

```text
问题：订单消费者积压
证据：RabbitMQ队列增长，CPU和MySQL仍有余量
修改：消费者并发数4 → 8
复测：相同1000请求
观察：积压消化时间、数据库连接等待、P99
结论：是否继续增加并发
```

如果增加消费者后Hikari等待激增，说明瓶颈转移到数据库，不能继续盲目增加线程。

## 17. 性能报告结构

保存到：

```text
docs/performance-report.md
```

报告包括：

```text
环境信息
应用版本
数据库/Redis/RabbitMQ拓扑
JVM参数
测试数据准备方式
JMeter线程组配置
基线结果
发现的问题
优化动作
优化后结果
业务正确性校验
剩余风险
```

## 18. 阶段验收清单

- [ ] Actuator端点受到访问保护。
- [ ] Prometheus指标可读取。
- [ ] 自定义指标没有高基数标签。
- [ ] 查询和抢票压测分开进行。
- [ ] 每次压测都校验订单与库存正确性。
- [ ] 能生成线程转储。
- [ ] 能读取GC日志。
- [ ] 能录制并打开JFR。
- [ ] 至少完成一次“证据→修改→复测”的优化。
- [ ] 输出性能报告和剩余风险。

## 19. 项目最终验收

完整项目完成时应满足：

```text
普通CRUD正确
MySQL不超卖
Redis Lua不超卖
同用户不重复购买
消息消费幂等
失败可以补偿和追踪
WebSocket通知有HTTP兜底
多实例能够运行
Redis Cluster故障转移经过验证
压测结果可重复
JVM调优有数据证据
```

## 20. 官方参考

- Spring Boot Actuator：`https://docs.spring.io/spring-boot/3.5/reference/actuator/`
- Micrometer：`https://docs.micrometer.io/micrometer/reference/`
- Java Flight Recorder API：`https://docs.oracle.com/en/java/javase/17/jfapi/`
- Java GC Tuning Guide：`https://docs.oracle.com/en/java/javase/17/gctuning/`
- Apache JMeter：`https://jmeter.apache.org/usermanual/`

## 21. 完整代码答案

本阶段的“答案”不是一组神奇JVM参数，而是一套可重复实验：先暴露指标和TraceId，再用固定脚本压测，保存吞吐、延迟、错误率、数据库正确性、GC和JFR证据，最后一次只调整一个变量。

### 21.1 Maven依赖

在 `<dependencies>` 中新增：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 21.2 完整监控配置

`src/main/resources/application-monitor.yml`

```yaml
spring:
  config:
    activate:
      on-profile: monitor
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:20}
      minimum-idle: ${DB_MIN_IDLE:5}
      connection-timeout: 3000
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,threaddump,heapdump
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: ${spring.application.name}
server:
  tomcat:
    threads:
      max: ${TOMCAT_MAX_THREADS:200}
    accept-count: ${TOMCAT_ACCEPT_COUNT:100}
```

监控端点在生产环境必须放到内网并配置鉴权；上面的开放配置仅用于本地学习。

### 21.3 TraceId过滤器

`src/main/java/com/example/flashticket/config/TraceIdFilter.java`

```java
package com.example.flashticket.config;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class TraceIdFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws ServletException,IOException {
        String traceId=req.getHeader("X-Trace-Id");
        if(traceId==null||traceId.isBlank())traceId=UUID.randomUUID().toString().replace("-","");
        MDC.put("traceId",traceId);res.setHeader("X-Trace-Id",traceId);
        try{chain.doFilter(req,res);}finally{MDC.remove("traceId");}
    }
}
```

在 `application.yml` 追加日志格式：

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [traceId=%X{traceId}] %logger{36} - %msg%n"
```

### 21.4 自定义业务指标

`src/main/java/com/example/flashticket/monitor/SeckillMetrics.java`

```java
package com.example.flashticket.monitor;
import io.micrometer.core.instrument.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
@Component
public class SeckillMetrics {
    private final Counter success; private final Counter failed; private final Timer consumeTimer;
    private final AtomicInteger processing=new AtomicInteger();
    public SeckillMetrics(MeterRegistry registry){
        success=Counter.builder("flash_ticket_seckill_success_total").description("成功订单数").register(registry);
        failed=Counter.builder("flash_ticket_seckill_failed_total").description("失败请求数").register(registry);
        consumeTimer=Timer.builder("flash_ticket_seckill_consume").description("消息消费耗时").publishPercentileHistogram().register(registry);
        Gauge.builder("flash_ticket_seckill_processing",processing,AtomicInteger::get).register(registry);
    }
    public Timer.Sample start(MeterRegistry registry){processing.incrementAndGet();return Timer.start(registry);}
    public void success(Timer.Sample sample){sample.stop(consumeTimer);processing.decrementAndGet();success.increment();}
    public void failed(Timer.Sample sample){sample.stop(consumeTimer);processing.decrementAndGet();failed.increment();}
}
```

### 21.5 完整替换消费者以记录指标

`src/main/java/com/example/flashticket/messaging/SeckillConsumer.java`

```java
package com.example.flashticket.messaging;
import com.example.flashticket.config.RabbitConfig;
import com.example.flashticket.dto.OrderDtos.CreateOrderRequest;
import com.example.flashticket.mapper.SeckillRequestMapper;
import com.example.flashticket.monitor.SeckillMetrics;
import com.example.flashticket.service.*;
import com.example.flashticket.vo.OrderVos.OrderVo;
import io.micrometer.core.instrument.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
@Component
public class SeckillConsumer {
    private final SeckillRequestMapper requests; private final TicketOrderService orders;
    private final RedisReservationService reservation; private final OrderNotificationService notifications;
    private final SeckillMetrics metrics; private final MeterRegistry registry;
    public SeckillConsumer(SeckillRequestMapper requests,TicketOrderService orders,
            RedisReservationService reservation,OrderNotificationService notifications,
            SeckillMetrics metrics,MeterRegistry registry){
        this.requests=requests;this.orders=orders;this.reservation=reservation;this.notifications=notifications;
        this.metrics=metrics;this.registry=registry;
    }
    @RabbitListener(queues=RabbitConfig.QUEUE)
    public void consume(SeckillMessage m){
        if(requests.claim(m.requestId())!=1)return;
        Timer.Sample sample=metrics.start(registry);
        try{
            OrderVo order=orders.create(new CreateOrderRequest(m.userId(),m.stockId()));
            requests.success(m.requestId(),order.id());notifications.success(m.userId(),m.requestId(),order.id());
            metrics.success(sample);
        }catch(RuntimeException e){
            reservation.rollback(m.stockId(),m.userId());
            String reason=e.getMessage()==null?"订单创建失败":e.getMessage();
            reason=reason.substring(0,Math.min(reason.length(),255));
            requests.fail(m.requestId(),reason);notifications.failed(m.userId(),m.requestId(),reason);
            metrics.failed(sample);
        }
    }
}
```

### 21.6 查询接口压测脚本

`load-test/k6-query.js`

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
export const options={stages:[
  {duration:'20s',target:20},{duration:'60s',target:20},{duration:'20s',target:0}
],thresholds:{http_req_failed:['rate<0.01'],http_req_duration:['p(95)<300']}};
const base=__ENV.BASE_URL||'http://localhost:8080';
const eventId=__ENV.EVENT_ID||'1';
export default function(){
  const res=http.get(`${base}/api/events/${eventId}`);
  check(res,{'status 200':r=>r.status===200,'business success':r=>r.json('code')===0});
  sleep(0.2);
}
```

### 21.7 抢票接口压测脚本

`load-test/k6-seckill.js`

```javascript
import http from 'k6/http';
import { check } from 'k6';
export const options={vus:50,iterations:500,thresholds:{http_req_duration:['p(95)<1000']}};
const base=__ENV.BASE_URL||'http://localhost:8080';
const stockId=Number(__ENV.STOCK_ID||1);
export default function(){
  const userId=100000+(__VU-1)*10+(__ITER%10)+1;
  const res=http.post(`${base}/api/seckill/requests`,JSON.stringify({userId,stockId}),
    {headers:{'Content-Type':'application/json','X-Trace-Id':`k6-${__VU}-${__ITER}`}});
  check(res,{'accepted or sold out':r=>r.status===200||r.status===400||r.status===409});
}
```

压测前必须先批量创建这些用户并预热票档；否则测到的是“用户不存在”，不是抢票性能。

`load-test/prepare-users.sql`

```sql
-- 只能在专用压测数据库执行。它会写入固定ID 100001至100500。
USE flash_ticket;
DELIMITER $$
DROP PROCEDURE IF EXISTS seed_load_users$$
CREATE PROCEDURE seed_load_users()
BEGIN
  DECLARE i INT DEFAULT 1;
  WHILE i <= 500 DO
    INSERT INTO app_user(id,username,phone,status)
    VALUES(100000+i,CONCAT('load-user-',i),CONCAT('138',LPAD(i,8,'0')),1)
    ON DUPLICATE KEY UPDATE username=VALUES(username),status=1;
    SET i=i+1;
  END WHILE;
END$$
CALL seed_load_users()$$
DROP PROCEDURE seed_load_users$$
DELIMITER ;
```

先执行该脚本，再运行k6脚本。每个虚拟用户循环使用自己对应的10个真实用户ID，避免请求落到不存在的用户。

### 21.8 JVM启动脚本

`deploy/run-with-diagnostics.ps1`

```powershell
param([int]$Port=8080,[string]$Profile='cluster,monitor')
$jar='target/flash-ticket-0.0.1-SNAPSHOT.jar'
if(-not (Test-Path -LiteralPath $jar)){throw "请先执行 .\mvnw.cmd clean package"}
New-Item -ItemType Directory -Force -Path 'logs' | Out-Null
java -Xms512m -Xmx512m `
  -Xlog:gc*:file=logs/gc-$Port.log:time,uptime,level,tags:filecount=5,filesize=20m `
  -XX:StartFlightRecording=filename=logs/flash-ticket-$Port.jfr,dumponexit=true,settings=profile `
  -Dserver.port=$Port -Dspring.profiles.active=$Profile -jar $jar
```

固定相同的堆大小是为了便于对比，不代表512MB一定适合生产环境。

### 21.9 完整实验和验收顺序

1. 用 `cluster,monitor` profile启动，检查 `/actuator/health` 和 `/actuator/prometheus`。
2. 运行查询脚本，记录吞吐、P95、错误率、CPU、堆和数据库连接池。
3. 重置订单、库存和Redis预热数据，再运行抢票脚本。
4. 用SQL核对成功订单数不超过初始库存、无负库存、无重复订单。
5. 查看GC日志和JFR；有证据后只修改一个参数，再重复完全相同的压测。
6. 若结果没有稳定改善，就撤销该参数，而不是继续叠加参数。

最终报告至少记录：环境、Git版本、数据规模、压测脚本参数、JVM参数、吞吐、P50/P95/P99、错误率、资源峰值、业务正确性SQL和结论。

